package com.voiceclean.app.stt

import android.content.Context
import com.voiceclean.app.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * whisper.cpp-backed [SttEngine] (English, no translation, no timestamps).
 * [threads] and [beam] (1 = greedy, up to 5) may be changed at any time.
 */
class WhisperEngine(
    context: Context,
    @Volatile var threads: Int = 4,
    @Volatile var beam: Int = 1,
) : SttEngine {
    private val nativeDir = context.applicationInfo.nativeLibraryDir
    @Volatile private var handle = 0L
    val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(path: String) = withContext(Dispatchers.IO) {
        check(NativeBridge.isLoaded) { "native library not loaded" }
        require(File(path).isFile) { "whisper model not found: $path" }
        unload()
        NativeBridge.initBackends(nativeDir)
        handle = NativeBridge.whisperLoad(path)
    }

    override fun transcribe(pcm: FloatArray, prompt: String?): String {
        val h = handle
        check(h != 0L) { "whisper model not loaded" }
        return NativeBridge.whisperTranscribe(h, pcm, threads, beam, prompt)
    }

    @Synchronized override fun unload() {
        val h = handle
        handle = 0L
        if (h != 0L) NativeBridge.whisperFree(h)
    }
}

/**
 * Uses [primary] once it has loaded a model successfully; until then (no model configured, load
 * failed, native lib missing) falls back to [fallback] so the UI keeps working.
 */
class FallbackSttEngine(private val primary: SttEngine, private val fallback: SttEngine) : SttEngine {
    @Volatile private var usePrimary = false
    @Volatile var lastError: Throwable? = null; private set
    val isReal: Boolean get() = usePrimary

    override suspend fun load(path: String) {
        usePrimary = false
        try {
            primary.load(path)
            usePrimary = true
            lastError = null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = e
            fallback.load(path)
        }
    }

    override fun transcribe(pcm: FloatArray, prompt: String?) =
        (if (usePrimary) primary else fallback).transcribe(pcm, prompt)

    override fun unload() { usePrimary = false; primary.unload(); fallback.unload() }
}
