// JNI-independent whisper.cpp wrapper.
#pragma once
#include <string>

struct VcWhisper;

// Loads backends (CPU variants etc.) from `dir` once per process. dir may be empty (static builds / default search).
void vc_init_backends(const char *dir);

// flash_attn is a context-creation parameter (needs a reload to change).
VcWhisper *vc_whisper_load(const char *path, bool flash_attn, std::string &err);

struct VcDecodeOpts {
    int threads = 4;
    int beam = 1;            // <=1 greedy, else beam search (clamped to 5)
    int audio_ctx = 0;       // encoder frames (50/s of audio); 0 = full 1500 (30 s)
    bool partial = false;    // provisional decode: single segment, no temperature fallback
};
// pcm: mono float 16 kHz. beam<=1 -> greedy, otherwise beam search (clamped to 5).
// Returns trimmed text; on failure returns "" and sets err (non-empty).
std::string vc_whisper_transcribe(VcWhisper *w, const float *pcm, int n, const VcDecodeOpts &o,
                                  const char *initial_prompt, std::string &err);
// "encode=..ms decode=..ms" of the last transcribe call (whisper's own timers).
std::string vc_whisper_last_timings(VcWhisper *w);
void vc_whisper_free(VcWhisper *w);
