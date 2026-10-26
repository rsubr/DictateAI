#include "whisper_core.h"
#include "whisper.h"
#include "ggml-backend.h"
#include <algorithm>
#include <exception>
#include <mutex>

struct VcWhisper {
    whisper_context *ctx = nullptr;
    std::mutex mu;
};

void vc_init_backends(const char *dir) {
    static std::once_flag once;
    std::call_once(once, [&] {
        if (dir && *dir) ggml_backend_load_all_from_path(dir);
        else ggml_backend_load_all();
    });
}

VcWhisper *vc_whisper_load(const char *path, std::string &err) {
    try {
        whisper_context_params cp = whisper_context_default_params();
        cp.use_gpu = false;
        cp.flash_attn = true;
        whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
        if (!ctx) { err = std::string("failed to load whisper model: ") + path; return nullptr; }
        auto *w = new VcWhisper();
        w->ctx = ctx;
        return w;
    } catch (const std::exception &e) { err = e.what(); }
    catch (...) { err = "unknown error loading whisper model"; }
    return nullptr;
}

std::string vc_whisper_transcribe(VcWhisper *w, const float *pcm, int n, int threads, int beam,
                                  const char *initial_prompt, std::string &err) {
    if (!w) { err = "whisper not loaded"; return ""; }
    if (n < 1600) return "";  // < 100 ms
    try {
        std::lock_guard<std::mutex> lk(w->mu);
        beam = std::min(beam, 5);
        whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH
                                                                      : WHISPER_SAMPLING_GREEDY);
        if (beam > 1) p.beam_search.beam_size = beam;
        p.n_threads = std::max(1, threads);
        p.language = "en";
        p.translate = false;
        p.detect_language = false;
        p.no_timestamps = true;
        p.print_progress = p.print_realtime = p.print_special = p.print_timestamps = false;
        p.suppress_blank = true;
        p.no_context = true;
        p.single_segment = false;
        p.initial_prompt = (initial_prompt && *initial_prompt) ? initial_prompt : nullptr;
        if (whisper_full(w->ctx, p, pcm, n) != 0) { err = "whisper_full failed"; return ""; }
        std::string out;
        int ns = whisper_full_n_segments(w->ctx);
        for (int i = 0; i < ns; i++) {
            const char *t = whisper_full_get_segment_text(w->ctx, i);
            if (t) out += t;
        }
        size_t a = out.find_first_not_of(" \t\n");
        size_t b = out.find_last_not_of(" \t\n");
        return a == std::string::npos ? "" : out.substr(a, b - a + 1);
    } catch (const std::exception &e) { err = e.what(); }
    catch (...) { err = "unknown error in whisper transcribe"; }
    return "";
}

void vc_whisper_free(VcWhisper *w) {
    if (!w) return;
    { std::lock_guard<std::mutex> lk(w->mu); if (w->ctx) whisper_free(w->ctx); w->ctx = nullptr; }
    delete w;
}
