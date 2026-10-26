package com.voiceclean.app.stt

import android.content.Context
import com.voiceclean.app.NativeBridge
import com.voiceclean.app.diag.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** Whisper flash-attention choice: AUTO = benchmark once per model and keep the faster one. */
object FlashMode {
    const val AUTO = "auto"
    const val ON = "on"
    const val OFF = "off"
}

/**
 * whisper.cpp-backed [SttEngine] (English, no translation, no timestamps).
 *
 * Tunables (all may be changed at any time except [flashMode], which applies at the next load):
 * - [threads], [beam] (final decodes; 1 = greedy, up to 5)
 * - partial decodes use a reduced encoder context: audio_ctx = ceil(window*50)+[ctxMarginFrames], at least
 *   [minAudioCtx], rounded up to a multiple of [CTX_STEP] (stable graph shapes), 0 (= full 30 s) when that exceeds 1500
 * - final decodes use the full context when [finalFullContext] is true (default; best accuracy)
 * After a model loads a warm-up decode runs so the first real decode is not cold.
 */
class WhisperEngine(
    private val context: Context,
    @Volatile var threads: Int = 4,
    @Volatile var beam: Int = 1,
) : SttEngine {
    private val nativeDir = context.applicationInfo.nativeLibraryDir
    @Volatile private var handle = 0L
    // transcribe() holds the read lock so unload() cannot free the native context under a running decode.
    private val lock = ReentrantReadWriteLock()
    private val prefs by lazy { context.getSharedPreferences("stt_bench", Context.MODE_PRIVATE) }
    val isLoaded: Boolean get() = handle != 0L

    @Volatile var flashMode: String = FlashMode.AUTO
    @Volatile var minAudioCtx: Int = 500
    @Volatile var ctxMarginFrames: Int = 50
    @Volatile var finalFullContext: Boolean = true
    /** Flash attention actually used by the loaded context. */
    @Volatile var activeFlash: Boolean = false; private set
    @Volatile var lastWarmupMs: Long = 0; private set
    @Volatile var lastLoadMs: Long = 0; private set
    @Volatile var lastDecodeMs: Long = 0; private set

    data class BenchRow(val flash: Boolean, val loadMs: Long, val warmupMs: Long, val partialMs: Long, val fullMs: Long)

    override suspend fun load(path: String) = withContext(Dispatchers.IO) {
        check(NativeBridge.isLoaded) { "native library not loaded" }
        require(File(path).isFile) { "whisper model not found: $path" }
        unload()
        NativeBridge.initBackends(nativeDir)
        val flash = when (flashMode) {
            FlashMode.ON -> true
            FlashMode.OFF -> false
            else -> storedChoice(path) ?: pickFromBenchmark(path)
        }
        val t0 = System.currentTimeMillis()
        val h = NativeBridge.whisperLoad(path, flash)
        lastLoadMs = System.currentTimeMillis() - t0
        activeFlash = flash
        lock.write { handle = h }
        val w0 = System.currentTimeMillis()
        runCatching { warmUp(h) }.onFailure { DebugLog.w("STT", "warm-up failed: ${it.message}") }
        lastWarmupMs = System.currentTimeMillis() - w0
        DebugLog.i("STT", "whisper ready: ${File(path).name} flash_attn=$flash threads=$threads load=${lastLoadMs}ms warmup=${lastWarmupMs}ms")
    }

    override suspend fun awaitReady() { check(handle != 0L) { "whisper model not loaded" } }

    // ---- benchmark / flash-attn choice ----
    private fun key(path: String) = "flash_" + File(path).name

    fun storedChoice(path: String): Boolean? = if (prefs.contains(key(path))) prefs.getBoolean(key(path), false) else null

    private fun pickAndStore(path: String, rows: List<BenchRow>): Boolean {
        // judge on the provisional (reduced-context) decode; the full-context time breaks near-ties
        val on = rows.first { it.flash }
        val off = rows.first { !it.flash }
        val score = { r: BenchRow -> r.partialMs * 3 + r.fullMs }
        val pickOn = score(on) <= score(off)
        prefs.edit().putBoolean(key(path), pickOn).apply()
        DebugLog.i("STT", "flash_attn benchmark: on partial=${on.partialMs}ms full=${on.fullMs}ms; " +
            "off partial=${off.partialMs}ms full=${off.fullMs}ms -> auto picks ${if (pickOn) "on" else "off"}")
        return pickOn
    }

    private fun pickFromBenchmark(path: String): Boolean = storedChoice(path) ?: run {
        benchmark(path)
        storedChoice(path) ?: true
    }

    /** Loads the model with flash_attn on and off (temporarily), measures a 5 s window, stores the faster choice. */
    fun benchmark(path: String): List<BenchRow> {
        check(NativeBridge.isLoaded) { "native library not loaded" }
        NativeBridge.initBackends(nativeDir)
        val pcm = syntheticSpeech(5f)
        val rows = listOf(true, false).map { flash ->
            val t0 = System.currentTimeMillis()
            val h = NativeBridge.whisperLoad(path, flash)
            val loadMs = System.currentTimeMillis() - t0
            try {
                val w0 = System.currentTimeMillis()
                warmUp(h)
                val warm = System.currentTimeMillis() - w0
                val ctx = ctxFor(5f)
                val p = (0 until 2).map { timed { NativeBridge.whisperTranscribe(h, pcm, threads, 1, ctx, true, null) } }.min()
                val f = timed { NativeBridge.whisperTranscribe(h, pcm, threads, 1, 0, false, null) }
                BenchRow(flash, loadMs, warm, p, f)
            } finally { NativeBridge.whisperFree(h) }
        }
        pickAndStore(path, rows)
        return rows
    }

    private inline fun timed(f: () -> Unit): Long { val t = System.nanoTime(); f(); return (System.nanoTime() - t) / 1_000_000 }

    private fun warmUp(h: Long) {
        // full-context decode touches every encoder/decoder weight page (mmap) and allocates all compute buffers;
        // the reduced-context one prepares the partial shape.
        val pcm = syntheticSpeech(1.5f)
        NativeBridge.whisperTranscribe(h, pcm, threads, 1, 0, false, null)
        NativeBridge.whisperTranscribe(h, pcm, threads, 1, ctxFor(1.5f), true, null)
    }

    /** 0 = full context. */
    fun ctxFor(windowSec: Float): Int {
        val raw = ceil(windowSec * 50f).toInt() + ctxMarginFrames
        val want = maxOf(minAudioCtx, raw)
        val rounded = (want + CTX_STEP - 1) / CTX_STEP * CTX_STEP
        return if (rounded >= 1500) 0 else rounded
    }

    override fun transcribe(pcm: FloatArray, prompt: String?): String = transcribe(pcm, prompt, DecodeOpts())

    override fun transcribe(pcm: FloatArray, prompt: String?, opts: DecodeOpts): String {
        return lock.read {
            val h = handle
            check(h != 0L) { "whisper model not loaded" }
            val sec = pcm.size / 16000f
            val ctx = if (opts.partial || !finalFullContext) ctxFor(sec) else 0
            val b = if (opts.partial) 1 else if (opts.beam > 0) opts.beam else beam
            val t0 = System.nanoTime()
            val out = NativeBridge.whisperTranscribe(h, pcm, threads, b, ctx, opts.partial, prompt)
            val ms = (System.nanoTime() - t0) / 1_000_000
            lastDecodeMs = ms
            DebugLog.i("STT", "decode ${if (opts.partial) "partial" else "final"} window=${"%.1f".format(sec)}s " +
                "audio_ctx=${if (ctx == 0) 1500 else ctx} beam=$b threads=$threads ms=$ms " +
                "(${NativeBridge.whisperTimings(h)}) chars=${out.length}")
            out
        }
    }

    override fun unload() {
        lock.write {
            val h = handle
            handle = 0L
            if (h != 0L) NativeBridge.whisperFree(h)
        }
    }

    companion object {
        const val CTX_STEP = 100

        /** Speech-like test signal: 130 Hz voiced buzz with formant-ish harmonics, 4 Hz syllable envelope, light noise. */
        fun syntheticSpeech(sec: Float): FloatArray {
            val n = (sec * 16000).toInt()
            val rnd = java.util.Random(1)
            return FloatArray(n) { i ->
                val t = i / 16000.0
                val env = 0.5 + 0.5 * sin(2 * PI * 4.0 * t)
                var v = 0.0
                for (k in 1..12) v += sin(2 * PI * 130.0 * k * t) / k
                (0.12 * env * v + 0.003 * rnd.nextGaussian()).toFloat()
            }
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
    /** Set by the app: loads the active speech model (used by [awaitReady] so recording can start before the model is up). */
    @Volatile var ensureLoaded: (suspend () -> Unit)? = null
    /** Thread count of the real engine (no-op for other primaries). */
    var threads: Int
        get() = (primary as? WhisperEngine)?.threads ?: 0
        set(v) { (primary as? WhisperEngine)?.threads = v }
    var flashMode: String
        get() = (primary as? WhisperEngine)?.flashMode ?: FlashMode.AUTO
        set(v) { (primary as? WhisperEngine)?.flashMode = v }
    val whisper: WhisperEngine? get() = primary as? WhisperEngine

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

    override suspend fun awaitReady() {
        if (usePrimary) return
        ensureLoaded?.invoke()
    }

    override fun transcribe(pcm: FloatArray, prompt: String?) =
        (if (usePrimary) primary else fallback).transcribe(pcm, prompt)

    override fun transcribe(pcm: FloatArray, prompt: String?, opts: DecodeOpts) =
        (if (usePrimary) primary else fallback).transcribe(pcm, prompt, opts)

    override fun unload() { usePrimary = false; primary.unload(); fallback.unload() }
}
