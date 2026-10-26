// JNI-independent whisper.cpp wrapper.
#pragma once
#include <string>

struct VcWhisper;

// Loads backends (CPU variants etc.) from `dir` once per process. dir may be empty (static builds / default search).
void vc_init_backends(const char *dir);

VcWhisper *vc_whisper_load(const char *path, std::string &err);
// pcm: mono float 16 kHz. beam<=1 -> greedy, otherwise beam search (clamped to 5).
// Returns trimmed text; on failure returns "" and sets err (non-empty).
std::string vc_whisper_transcribe(VcWhisper *w, const float *pcm, int n, int threads, int beam,
                                  const char *initial_prompt, std::string &err);
void vc_whisper_free(VcWhisper *w);
