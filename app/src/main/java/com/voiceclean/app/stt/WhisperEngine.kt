package com.voiceclean.app.stt

import android.content.Context
import com.voiceclean.app.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
    // transcribe() holds the read lock so unload() cannot free the native context under a running decode.
    private val lock = ReentrantReadWriteLock()
    val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(path: String) = withContext(Dispatchers.IO) {
        check(NativeBridge.isLoaded) { "native library not loaded" }
        require(File(path).isFile) { "whisper model not found: $path" }
        unload()
        NativeBridge.initBackends(nativeDir)
        val h = NativeBridge.whisperLoad(path)
        lock.write { handle = h }
    }

    override fun transcribe(pcm: FloatArray, prompt: String?): String {
        return lock.read {
            val h = handle
            check(h != 0L) { "whisper model not loaded" }
            NativeBridge.whisperTranscribe(h, pcm, threads, beam, prompt)
        }
    }

    override fun unload() {
        lock.write {
            val h = handle
            handle = 0L
            if (h != 0L) NativeBridge.whisperFree(h)
        }
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
    /** Thread count of the real engine (no-op for other primaries). */
    var threads: Int
        get() = (primary as? WhisperEngine)?.threads ?: 0
        set(v) { (primary as? WhisperEngine)?.threads = v }

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
