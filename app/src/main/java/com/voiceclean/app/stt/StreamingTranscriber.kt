package com.voiceclean.app.stt

import com.voiceclean.app.audio.SAMPLE_RATE
import com.voiceclean.app.audio.Vad
import com.voiceclean.app.audio.rms
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class Transcript(val committed: String = "", val partial: String = "") {
    val full: String get() = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Sliding-window streaming on top of a non-streaming [SttEngine] (D5).
 *
 * - Audio is accumulated in an utterance window. Every [Config.decodeIntervalSec] of audio the window is decoded
 *   (never overlapping: a partial is skipped while the engine is busy) and published as `partial`.
 * - When VAD reports [Config.finalizeSilenceSec] of silence after speech (or the window reaches [Config.maxWindowSec])
 *   the window is queued for a final decode, its text is committed and a new window starts. Committed text is passed
 *   as the initial prompt (last [Config.promptChars] chars) for context.
 * - After [Config.autoStopSilenceSec] without speech the session ends by itself ([autoStopped] becomes true).
 * - Time is measured in audio samples, so behaviour is deterministic and independent of wall-clock.
 */
class StreamingTranscriber(
    private val engine: SttEngine,
    private val vad: Vad,
    private val config: Config = Config(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Config(
        val decodeIntervalSec: Float = 1.0f,
        val maxWindowSec: Float = 25f,
        val finalizeSilenceSec: Float = 0.8f,
        val autoStopSilenceSec: Float = 8f,
        val minSpeechSec: Float = 0.3f,
        val prerollSec: Float = 0.5f,
        val promptChars: Int = 224,
    )

    private val _transcript = MutableStateFlow(Transcript())
    val transcript: StateFlow<Transcript> = _transcript.asStateFlow()
    private val _level = MutableStateFlow(0f)
    /** Smoothed input level 0..1 for UI meters. */
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _autoStopped = MutableStateFlow(false)
    val autoStopped: StateFlow<Boolean> = _autoStopped.asStateFlow()
    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    @Volatile private var stopRequested = false
    private var session: Job? = null
    private var collectJob: Job? = null

    private class Req(val pcm: FloatArray, val speechSec: Float, val windowId: Int, val final: Boolean)

    /** Starts a session; returns immediately. [audio] is collected until it ends, [stop] is called or auto-stop fires. */
    fun start(scope: CoroutineScope, audio: Flow<FloatArray>): Job {
        check(session?.isActive != true) { "already running" }
        stopRequested = false
        _transcript.value = Transcript()
        _autoStopped.value = false
        _error.value = null
        vad.reset()
        val j = scope.launch(dispatcher) { runSession(this, audio) }
        session = j
        return j
    }

    /** Stops capturing, flushes a final decode of the pending window and returns the full committed text. */
    suspend fun stop(): String {
        stopRequested = true
        collectJob?.cancel()
        session?.join()
        return _transcript.value.full
    }

    private suspend fun runSession(scope: CoroutineScope, audio: Flow<FloatArray>) {
        val queue = Channel<Req>(Channel.UNLIMITED)
        val pending = AtomicInteger(0)
        val currentWindow = AtomicInteger(0)
        val worker = scope.launch(dispatcher) {
            for (r in queue) {
                try {
                    val prompt = _transcript.value.committed.takeLast(config.promptChars).trim().ifEmpty { null }
                    val raw = withContext(dispatcher) { engine.transcribe(r.pcm, prompt) }
                    val text = HallucinationFilter.clean(raw, r.speechSec)
                    if (r.final) {
                        _transcript.update { t ->
                            if (text.isEmpty()) t.copy(partial = "")
                            else t.copy(committed = if (t.committed.isEmpty()) text else t.committed + " " + text, partial = "")
                        }
                    } else if (r.windowId == currentWindow.get()) {
                        _transcript.update { it.copy(partial = text) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _error.value = e
                } finally {
                    pending.decrementAndGet()
                }
            }
        }

        // window state
        var window = FloatArray(SAMPLE_RATE * 30)
        var len = 0
        var speechSamples = 0
        var trailingSilence = 0       // samples of silence since last speech frame (within window)
        var sinceDecode = 0
        var sinceSpeechTotal = 0      // samples since last speech anywhere (for auto-stop)
        var smooth = 0f

        fun submit(final: Boolean) {
            val pcm = window.copyOf(len)
            pending.incrementAndGet()
            queue.trySend(Req(pcm, speechSamples.toFloat() / SAMPLE_RATE, currentWindow.get(), final))
        }
        fun resetWindow() {
            len = 0; speechSamples = 0; trailingSilence = 0; sinceDecode = 0; currentWindow.incrementAndGet()
        }
        val intervalSamples = (config.decodeIntervalSec * SAMPLE_RATE).toInt()
        val maxSamples = (config.maxWindowSec * SAMPLE_RATE).toInt()
        val finalizeSamples = (config.finalizeSilenceSec * SAMPLE_RATE).toInt()
        val autoStopSamples = (config.autoStopSilenceSec * SAMPLE_RATE).toInt()
        val prerollSamples = (config.prerollSec * SAMPLE_RATE).toInt()
        val minSpeechSamples = (config.minSpeechSec * SAMPLE_RATE).toInt()

        try {
            val collector = scope.launch {
                try { audio.takeWhile { !stopRequested }.collect { chunk ->
                    if (len + chunk.size > window.size) window = window.copyOf(maxOf(window.size * 2, len + chunk.size))
                    chunk.copyInto(window, len)
                    len += chunk.size
                    sinceDecode += chunk.size

                    smooth = 0.6f * smooth + 0.4f * (rms(chunk) * 6f).coerceIn(0f, 1f)
                    _level.value = smooth

                    val speech = vad.process(chunk)
                    if (speech) {
                        speechSamples += chunk.size; trailingSilence = 0; sinceSpeechTotal = 0
                    } else {
                        trailingSilence += chunk.size; sinceSpeechTotal += chunk.size
                    }

                    if (speechSamples < minSpeechSamples) {
                        // nothing worth decoding yet: keep only a short pre-roll
                        if (len > prerollSamples + chunk.size) {
                            val drop = len - prerollSamples
                            window.copyInto(window, 0, drop, len)
                            len -= drop
                        }
                        sinceDecode = 0
                    } else if (trailingSilence >= finalizeSamples || len >= maxSamples) {
                        submit(true)
                        resetWindow()
                        _transcript.update { it.copy(partial = "") }
                    } else if (sinceDecode >= intervalSamples && pending.get() == 0) {
                        sinceDecode = 0
                        submit(false)
                    }

                    if (sinceSpeechTotal >= autoStopSamples) {
                        _autoStopped.value = true
                        stopRequested = true
                    }
                } } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Throwable) { _error.value = e }
            }
            collectJob = collector
            collector.join()
            // flush the pending window
            if (speechSamples >= minSpeechSamples && len > 0) submit(true)
        } finally {
            queue.close()
            worker.join()
            _level.value = 0f
        }
    }
}
