#include "llama_core.h"
#include "llama.h"
#include <algorithm>
#include <exception>
#include <mutex>
#include <vector>

struct VcLlama {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
    std::mutex mu;
};

static void ensure_backend() {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
}

VcLlama *vc_llama_load(const char *path, int n_ctx, int threads, std::string &err) {
    try {
        ensure_backend();
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        llama_model *m = llama_model_load_from_file(path, mp);
        if (!m) { err = std::string("failed to load GGUF model: ") + path; return nullptr; }
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = n_ctx > 0 ? n_ctx : 4096;
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        cp.n_threads = cp.n_threads_batch = std::max(1, threads);
        llama_context *c = llama_init_from_model(m, cp);
        if (!c) { llama_model_free(m); err = "failed to create llama context (out of memory?)"; return nullptr; }
        auto *l = new VcLlama();
        l->model = m; l->ctx = c;
        l->n_ctx = (int)llama_n_ctx(c);
        l->n_batch = (int)llama_n_batch(c);
        return l;
    } catch (const std::exception &e) { err = e.what(); }
    catch (...) { err = "unknown error loading llama model"; }
    return nullptr;
}

static std::string apply_template(llama_model *model, const std::string &system, const std::string &user) {
    std::vector<llama_chat_message> msgs;
    if (!system.empty()) msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});
    const char *tmpl = llama_model_chat_template(model, nullptr);
    std::vector<char> buf(2 * (system.size() + user.size()) + 1024);
    auto run = [&](const char *t) {
        int n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
        if (n > (int)buf.size()) {
            buf.resize(n + 16);
            n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(), (int)buf.size());
        }
        return n;
    };
    int n = tmpl ? run(tmpl) : -1;
    if (n < 0) n = run("chatml");  // unsupported/missing embedded template -> ChatML
    if (n < 0) return (system.empty() ? "" : system + "\n\n") + user;  // last resort: raw text
    return std::string(buf.data(), n);
}

// Length of the longest prefix of s that does not end in an incomplete UTF-8 sequence.
static size_t utf8_complete_len(const std::string &s) {
    size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char)s[i - 1];
        if ((c & 0xC0) == 0x80) { i--; back++; continue; }  // continuation
        int need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return (n - (i - 1) < (size_t)need) ? i - 1 : n;
    }
    return n;
}

bool vc_llama_generate(VcLlama *l, const std::string &system, const std::string &user,
                       const VcGenOpts &o, const std::function<bool(const std::string &)> &on_piece,
                       const std::atomic<bool> *cancel, std::string &err) {
    if (!l) { err = "llama not loaded"; return false; }
    llama_sampler *smpl = nullptr;
    try {
        std::lock_guard<std::mutex> lk(l->mu);
        const llama_vocab *vocab = llama_model_get_vocab(l->model);
        std::string prompt = apply_template(l->model, system, user);

        int cap = (int)prompt.size() + 16;
        std::vector<llama_token> toks(cap);
        int nt = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), toks.data(), cap, true, true);
        if (nt < 0) {
            toks.resize(-nt);
            nt = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), toks.data(), (int)toks.size(), true, true);
        }
        if (nt <= 0) { err = "tokenization failed"; return false; }
        toks.resize(nt);
        if (nt >= l->n_ctx - 8) { err = "prompt too long for context (" + std::to_string(nt) + " tokens, n_ctx " + std::to_string(l->n_ctx) + ")"; return false; }
        int max_new = std::min(o.max_tokens > 0 ? o.max_tokens : 512, l->n_ctx - nt - 1);

        llama_memory_clear(llama_get_memory(l->ctx), true);

        auto sp = llama_sampler_chain_default_params();
        smpl = llama_sampler_chain_init(sp);
        if (o.repeat_penalty > 1.0f && o.repeat_last_n != 0)
            llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), o.repeat_last_n, o.repeat_penalty, 0.f, 0.f));
        if (o.temperature <= 0.f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
        } else {
            if (o.top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(o.top_k));
            if (o.top_p < 1.f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(o.top_p, 1));
            if (o.min_p > 0.f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(o.min_p, 1));
            llama_sampler_chain_add(smpl, llama_sampler_init_temp(o.temperature));
            llama_sampler_chain_add(smpl, llama_sampler_init_dist(o.seed));
        }

        // Prefill in n_batch chunks.
        for (int i = 0; i < nt; i += l->n_batch) {
            if (cancel && cancel->load()) { llama_sampler_free(smpl); return true; }
            int n = std::min(l->n_batch, nt - i);
            llama_batch b = llama_batch_get_one(toks.data() + i, n);
            if (llama_decode(l->ctx, b) != 0) { err = "llama_decode failed (prefill)"; llama_sampler_free(smpl); return false; }
        }

        std::string pending;
        char piece[256];
        for (int i = 0; i < max_new; i++) {
            if (cancel && cancel->load()) break;
            llama_token t = llama_sampler_sample(smpl, l->ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) break;
            llama_sampler_accept(smpl, t);
            int pn = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, false);
            if (pn < 0) {
                std::vector<char> big(-pn);
                pn = llama_token_to_piece(vocab, t, big.data(), (int)big.size(), 0, false);
                if (pn > 0) pending.append(big.data(), pn);
            } else if (pn > 0) {
                pending.append(piece, pn);
            }
            size_t ok = utf8_complete_len(pending);
            if (ok > 0) {
                std::string chunk = pending.substr(0, ok);
                pending.erase(0, ok);
                if (!on_piece(chunk)) break;
            }
            llama_batch b = llama_batch_get_one(&t, 1);
            if (llama_decode(l->ctx, b) != 0) { err = "llama_decode failed"; llama_sampler_free(smpl); return false; }
        }
        // Flush any trailing bytes (invalid/incomplete sequence at end): replace to keep JNI strings valid.
        if (!pending.empty() && !(cancel && cancel->load())) {
            // drop incomplete tail rather than emitting invalid UTF-8
        }
        llama_sampler_free(smpl);
        return true;
    } catch (const std::exception &e) { err = e.what(); }
    catch (...) { err = "unknown error in llama generate"; }
    if (smpl) llama_sampler_free(smpl);
    return false;
}

void vc_llama_free(VcLlama *l) {
    if (!l) return;
    { std::lock_guard<std::mutex> lk(l->mu);
      if (l->ctx) llama_free(l->ctx);
      if (l->model) llama_model_free(l->model);
      l->ctx = nullptr; l->model = nullptr; }
    delete l;
}
