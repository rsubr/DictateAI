// JNI-independent llama.cpp wrapper.
#pragma once
#include <atomic>
#include <functional>
#include <string>

struct VcLlama;

struct VcGenOpts {
    int max_tokens = 512;
    float temperature = 0.2f;
    float top_p = 0.95f;
    int top_k = 40;
    float min_p = 0.05f;
    float repeat_penalty = 1.1f;
    int repeat_last_n = 64;
    unsigned seed = 0xFFFFFFFFu;  // random
};

VcLlama *vc_llama_load(const char *path, int n_ctx, int threads, std::string &err);
// Streams complete-UTF-8 chunks to on_piece (return false to stop). `cancel` may be null.
// Returns true on success (including cancelled/EOS/max tokens); false + err on failure.
bool vc_llama_generate(VcLlama *l, const std::string &system, const std::string &user,
                       const VcGenOpts &opts, const std::function<bool(const std::string &)> &on_piece,
                       const std::atomic<bool> *cancel, std::string &err);
void vc_llama_free(VcLlama *l);
