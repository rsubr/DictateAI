package com.voiceclean.app

/**
 * Loads libvoiceclean_jni.so and exposes the raw JNI entry points. Use the typed wrappers
 * (stt.WhisperEngine, llm.LlamaCppEngine) instead of calling these directly.
 * Handles are opaque native pointers (0 = invalid). Native errors surface as IllegalStateException.
 */
object NativeBridge {
    val isLoaded: Boolean = try {
        System.loadLibrary("voiceclean_jni"); true
    } catch (t: Throwable) { false }

    /** Receives UTF-8-safe text chunks; return false to stop generation. */
    fun interface TokenCallback { fun onToken(piece: String): Boolean }

    private external fun nativeVersion(): String
    /** Loads ggml backends (runtime-dispatched CPU variants) from [dir]; call once with applicationInfo.nativeLibraryDir. */
    private external fun nativeInitBackends(dir: String)

    external fun whisperLoad(path: String): Long
    external fun whisperTranscribe(handle: Long, pcm: FloatArray, threads: Int, beam: Int, initialPrompt: String?): String
    external fun whisperFree(handle: Long)

    external fun llamaLoad(path: String, nCtx: Int, threads: Int): Long
    external fun llamaGenerate(
        handle: Long, system: String, user: String, maxTokens: Int, temp: Float, topP: Float,
        topK: Int, minP: Float, repeatPenalty: Float, cb: TokenCallback,
    )
    external fun llamaCancel(handle: Long)
    external fun llamaFree(handle: Long)

    @Volatile private var backendsReady = false
    @Synchronized fun initBackends(nativeLibDir: String) {
        if (!backendsReady) { nativeInitBackends(nativeLibDir); backendsReady = true }
    }

    fun version(): String = if (isLoaded) nativeVersion() else "native lib not loaded"
}
