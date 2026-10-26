package com.voiceclean.app

/** Loads libvoiceclean_jni.so. Phase B adds llama/whisper JNI entry points here or in sibling classes. */
object NativeBridge {
    val isLoaded: Boolean = try {
        System.loadLibrary("voiceclean_jni"); true
    } catch (t: Throwable) { false }

    private external fun nativeVersion(): String

    fun version(): String = if (isLoaded) nativeVersion() else "native lib not loaded"
}
