// JNI glue for VoiceClean. All logic lives in core/ (JNI-free); this file only
// marshals arguments, converts strings (real UTF-8 <-> UTF-16, NOT modified
// UTF-8, so emoji etc. are safe) and guarantees no C++ exception crosses JNI.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <deque>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"
#include "whisper.h"
#include "llama_core.h"
#include "whisper_core.h"

#define TAG "VoiceCleanJNI"

namespace {

struct LlamaH {
    VcLlama *l;
    std::atomic<bool> cancel{false};
};

// Ring buffer of recent native (ggml/llama/whisper) log lines, readable from Kotlin for diagnostics.
// Repeated identical lines are collapsed ("x N"); lines mentioning error/warn/fail live in a second ring so they
// survive chatter.
struct LogLine { long seq; std::string text; int count; };
std::mutex g_log_mu;
std::deque<LogLine> g_log, g_keep;
std::string g_partial;  // ggml logs may arrive in fragments (CONT level)
long g_seq = 0;
constexpr size_t kMaxLogLines = 1500;
constexpr size_t kMaxKeepLines = 300;

bool is_important(const std::string &s) {
    std::string l;
    l.reserve(s.size());
    for (char c : s) l += (char)tolower((unsigned char)c);
    return l.find("error") != std::string::npos || l.find("warn") != std::string::npos ||
           l.find("fail") != std::string::npos;
}

void push_line_locked(const std::string &text) {
    // collapse against one of the last few lines (identical lines may be interleaved with one other line)
    size_t look = std::min<size_t>(g_log.size(), 4);
    for (size_t i = 0; i < look; i++) {
        LogLine &q = g_log[g_log.size() - 1 - i];
        if (q.text == text) { q.count++; return; }
    }
    LogLine l{++g_seq, text, 1};
    g_log.push_back(l);
    if (g_log.size() > kMaxLogLines) g_log.pop_front();
    if (is_important(text)) {
        g_keep.push_back(l);
        if (g_keep.size() > kMaxKeepLines) g_keep.pop_front();
    }
}

void ring_push(const char *text) {
    std::lock_guard<std::mutex> lk(g_log_mu);
    g_partial += text;
    size_t pos;
    while ((pos = g_partial.find('\n')) != std::string::npos) {
        push_line_locked(g_partial.substr(0, pos));
        g_partial.erase(0, pos + 1);
    }
    if (g_partial.size() > 4096) { push_line_locked(g_partial); g_partial.clear(); }
}

void android_log(ggml_log_level level, const char *text, void *) {
    if (!text) return;
    if (level != GGML_LOG_LEVEL_DEBUG) ring_push(text);
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
             : level == GGML_LOG_LEVEL_DEBUG ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO;
    __android_log_write(prio, "ggml", text);
}

void throw_java(JNIEnv *env, const char *cls, const std::string &msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c) { env->ThrowNew(c, msg.c_str()); env->DeleteLocalRef(c); }
}

std::string to_utf8(JNIEnv *env, jstring js) {
    std::string out;
    if (!js) return out;
    jsize n = env->GetStringLength(js);
    const jchar *u = env->GetStringChars(js, nullptr);
    if (!u) return out;
    for (jsize i = 0; i < n; i++) {
        uint32_t c = u[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c < 0xE000) {
            c = 0xFFFD;
        }
        if (c < 0x80) out += (char)c;
        else if (c < 0x800) { out += (char)(0xC0 | (c >> 6)); out += (char)(0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { out += (char)(0xE0 | (c >> 12)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
        else { out += (char)(0xF0 | (c >> 18)); out += (char)(0x80 | ((c >> 12) & 0x3F)); out += (char)(0x80 | ((c >> 6) & 0x3F)); out += (char)(0x80 | (c & 0x3F)); }
    }
    env->ReleaseStringChars(js, u);
    return out;
}

jstring from_utf8(JNIEnv *env, const std::string &s) {
    std::vector<jchar> u;
    u.reserve(s.size());
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        uint32_t cp = 0xFFFD;
        int len = 1;
        if (c < 0x80) { cp = c; }
        else if ((c & 0xE0) == 0xC0 && i + 1 < n && (s[i + 1] & 0xC0) == 0x80) { cp = ((c & 0x1F) << 6) | (s[i + 1] & 0x3F); len = 2; }
        else if ((c & 0xF0) == 0xE0 && i + 2 < n && (s[i + 1] & 0xC0) == 0x80 && (s[i + 2] & 0xC0) == 0x80) { cp = ((c & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F); len = 3; }
        else if ((c & 0xF8) == 0xF0 && i + 3 < n && (s[i + 1] & 0xC0) == 0x80 && (s[i + 2] & 0xC0) == 0x80 && (s[i + 3] & 0xC0) == 0x80) { cp = ((c & 0x07) << 18) | ((s[i + 1] & 0x3F) << 12) | ((s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F); len = 4; }
        i += len;
        if (cp >= 0x10000) { cp -= 0x10000; u.push_back((jchar)(0xD800 + (cp >> 10))); u.push_back((jchar)(0xDC00 + (cp & 0x3FF))); }
        else u.push_back((jchar)cp);
    }
    return env->NewString(u.data(), (jsize)u.size());
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_voiceclean_app_NativeBridge_nativeVersion(JNIEnv *env, jobject) {
    return from_utf8(env, "voiceclean_jni: llama.cpp b11899 + whisper.cpp v1.9.5 (shared ggml, CPU variants)");
}

JNIEXPORT void JNICALL
Java_com_voiceclean_app_NativeBridge_nativeInitBackends(JNIEnv *env, jobject, jstring dir) {
    try {
        llama_log_set(android_log, nullptr);
        whisper_log_set(android_log, nullptr);
        vc_init_backends(to_utf8(env, dir).c_str());
    } catch (...) { throw_java(env, "java/lang/RuntimeException", "backend init failed"); }
}

JNIEXPORT jstring JNICALL
Java_com_voiceclean_app_NativeBridge_nativeLog(JNIEnv *env, jobject) {
    std::string out;
    {
        std::lock_guard<std::mutex> lk(g_log_mu);
        // merge the main ring with the always-kept important lines (by sequence number)
        long first = g_log.empty() ? 0 : g_log.front().seq;
        std::deque<const LogLine *> all;
        for (auto &l : g_keep) if (l.seq < first) all.push_back(&l);
        for (auto &l : g_log) all.push_back(&l);
        for (auto *l : all) {
            out += l->text;
            if (l->count > 1) out += " x" + std::to_string(l->count);
            out += '\n';
        }
        out += g_partial;
    }
    return from_utf8(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_voiceclean_app_NativeBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    const char *s = llama_print_system_info();
    return from_utf8(env, s ? s : "");
}

// ---- Whisper ----------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_voiceclean_app_NativeBridge_whisperLoad(JNIEnv *env, jobject, jstring path, jboolean flashAttn) {
    std::string err;
    VcWhisper *w = nullptr;
    try { w = vc_whisper_load(to_utf8(env, path).c_str(), flashAttn == JNI_TRUE, err); } catch (...) { err = "native exception"; }
    if (!w) { throw_java(env, "java/lang/IllegalStateException", err); return 0; }
    return (jlong)(intptr_t)w;
}

JNIEXPORT jstring JNICALL
Java_com_voiceclean_app_NativeBridge_whisperTranscribe(JNIEnv *env, jobject, jlong h, jfloatArray pcm,
                                                       jint threads, jint beam, jint audioCtx, jboolean partial,
                                                       jstring prompt) {
    if (!h) { throw_java(env, "java/lang/IllegalStateException", "whisper not loaded"); return nullptr; }
    std::string err, out;
    try {
        jsize n = env->GetArrayLength(pcm);
        jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
        if (!data) { throw_java(env, "java/lang/OutOfMemoryError", "pcm"); return nullptr; }
        std::string p = prompt ? to_utf8(env, prompt) : std::string();
        VcDecodeOpts o;
        o.threads = threads; o.beam = beam; o.audio_ctx = audioCtx; o.partial = partial == JNI_TRUE;
        out = vc_whisper_transcribe((VcWhisper *)(intptr_t)h, data, n, o, p.empty() ? nullptr : p.c_str(), err);
        env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    } catch (...) { err = "native exception"; }
    if (!err.empty()) { throw_java(env, "java/lang/IllegalStateException", err); return nullptr; }
    return from_utf8(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_voiceclean_app_NativeBridge_whisperTimings(JNIEnv *env, jobject, jlong h) {
    if (!h) return from_utf8(env, "");
    std::string s;
    try { s = vc_whisper_last_timings((VcWhisper *)(intptr_t)h); } catch (...) {}
    return from_utf8(env, s);
}

JNIEXPORT void JNICALL
Java_com_voiceclean_app_NativeBridge_whisperFree(JNIEnv *, jobject, jlong h) {
    try { vc_whisper_free((VcWhisper *)(intptr_t)h); } catch (...) {}
}

// ---- Llama ------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_voiceclean_app_NativeBridge_llamaLoad(JNIEnv *env, jobject, jstring path, jint nCtx, jint threads) {
    std::string err;
    VcLlama *l = nullptr;
    try { l = vc_llama_load(to_utf8(env, path).c_str(), nCtx, threads, err); } catch (...) { err = "native exception"; }
    if (!l) { throw_java(env, "java/lang/IllegalStateException", err); return 0; }
    return (jlong)(intptr_t) new LlamaH{l};
}

// callback: com.voiceclean.app.NativeBridge$TokenCallback { boolean onToken(String) }
JNIEXPORT void JNICALL
Java_com_voiceclean_app_NativeBridge_llamaGenerate(JNIEnv *env, jobject, jlong h, jstring system, jstring user,
                                                   jint maxTokens, jfloat temp, jfloat topP, jint topK,
                                                   jfloat minP, jfloat repeatPenalty, jobject cb) {
    if (!h) { throw_java(env, "java/lang/IllegalStateException", "llama not loaded"); return; }
    auto *H = (LlamaH *)(intptr_t)h;
    std::string err;
    try {
        jclass cls = env->GetObjectClass(cb);
        jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        if (!mid) { throw_java(env, "java/lang/NoSuchMethodError", "onToken"); return; }
        VcGenOpts o;
        o.max_tokens = maxTokens; o.temperature = temp; o.top_p = topP; o.top_k = topK;
        o.min_p = minP; o.repeat_penalty = repeatPenalty;
        std::string sys = to_utf8(env, system), usr = to_utf8(env, user);
        H->cancel = false;
        bool jexc = false;
        bool ok = vc_llama_generate(H->l, sys, usr, o, [&](const std::string &piece) {
            jstring js = from_utf8(env, piece);
            jboolean cont = env->CallBooleanMethod(cb, mid, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); jexc = true; return false; }
            return cont == JNI_TRUE;
        }, &H->cancel, err);
        (void)jexc;
        if (!ok) throw_java(env, "java/lang/IllegalStateException", err);
    } catch (const std::exception &e) {
        throw_java(env, "java/lang/IllegalStateException", e.what());
    } catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "native exception");
    }
}

JNIEXPORT void JNICALL
Java_com_voiceclean_app_NativeBridge_llamaCancel(JNIEnv *, jobject, jlong h) {
    if (h) ((LlamaH *)(intptr_t)h)->cancel = true;
}

JNIEXPORT void JNICALL
Java_com_voiceclean_app_NativeBridge_llamaFree(JNIEnv *, jobject, jlong h) {
    if (!h) return;
    auto *H = (LlamaH *)(intptr_t)h;
    H->cancel = true;
    try { vc_llama_free(H->l); } catch (...) {}
    delete H;
}

}  // extern "C"
