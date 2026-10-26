#include <chrono>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <vector>
#include "llama_core.h"
#include "whisper_core.h"

static std::vector<float> read_wav16(const char *p) {  // 16 kHz mono PCM16 WAV, 44-byte header assumed
    std::ifstream f(p, std::ios::binary);
    std::vector<char> b((std::istreambuf_iterator<char>(f)), {});
    std::vector<float> out;
    size_t st = 44; for (size_t k = 12; k + 8 < b.size();) { uint32_t sz; memcpy(&sz, &b[k + 4], 4); if (!memcmp(&b[k], "data", 4)) { st = k + 8; break; } k += 8 + sz; }
    for (size_t i = st; i + 1 < b.size(); i += 2) out.push_back((int16_t)((uint8_t)b[i] | ((uint8_t)b[i + 1] << 8)) / 32768.f);
    return out;
}
int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: hosttest whisper <model> <wav> | llama <model>\n"); return 2; }
    vc_init_backends("");
    std::string err;
    if (!strcmp(argv[1], "whisper") && argc >= 4) {
        auto *w = vc_whisper_load(argv[2], err);
        if (!w) { fprintf(stderr, "ERR %s\n", err.c_str()); return 1; }
        auto pcm = read_wav16(argv[3]);
        for (int beam : {1, 3}) {
            auto t0 = std::chrono::steady_clock::now();
            std::string s = vc_whisper_transcribe(w, pcm.data(), (int)pcm.size(), 8, beam, nullptr, err);
            double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            printf("[beam %d, %.0f ms] %s\n", beam, ms, s.c_str());
        }
        vc_whisper_free(w);
    } else if (!strcmp(argv[1], "llama")) {
        auto *l = vc_llama_load(argv[2], 2048, 8, err);
        if (!l) { fprintf(stderr, "ERR %s\n", err.c_str()); return 1; }
        VcGenOpts o; o.max_tokens = 200;
        std::string sys = "You fix spelling, punctuation and grammar. Output only the corrected text.";
        std::string usr = "um so i wanted to uh send you the the report tomorow cafe \xF0\x9F\x98\x80 and its done";
        std::atomic<bool> cancel{false};
        int pieces = 0;
        bool ok = vc_llama_generate(l, sys, usr, o, [&](const std::string &p) { fputs(p.c_str(), stdout); fflush(stdout); pieces++; return true; }, &cancel, err);
        printf("\n[ok=%d pieces=%d err=%s]\n", ok, pieces, err.c_str());
        // cancel test
        int n = 0;
        ok = vc_llama_generate(l, "", "Write a long story.", o, [&](const std::string &) { return ++n < 5; }, &cancel, err);
        printf("[cancel test: ok=%d pieces=%d]\n", ok, n);
        vc_llama_free(l);
    }
    return 0;
}
