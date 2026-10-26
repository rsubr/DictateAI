// Native stub: verifies the NDK/CMake/JNI pipeline. Phase B replaces/extends
// this with llama.cpp and whisper.cpp glue.
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_voiceclean_app_NativeBridge_nativeVersion(JNIEnv *env, jobject /*thiz*/) {
    std::string v = "voiceclean_jni stub (no llama/whisper yet)";
    return env->NewStringUTF(v.c_str());
}
