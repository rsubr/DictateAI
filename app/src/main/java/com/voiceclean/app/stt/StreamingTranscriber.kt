package com.voiceclean.app.stt

import com.voiceclean.app.audio.SAMPLE_RATE
import com.voiceclean.app.audio.Vad
import com.voiceclean.app.audio.rms
import com.voiceclean.app.diag.DebugLog
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * [committed] = final (accurate) text of finished utterances. [partial] = everything not yet final: provisional text of
 * finished utterances still waiting for their accurate re-decode plus the provisional text of the current utterance.
 */
data class Transcript(val committed: String = "", val partial: String = "") {
    val full: String get() = when {
        partial.isBlank() -> committed
        committed.isBlank() -> partial
        committed.endsWith("\n") -> committed + partial   // paragraph break requested by the user
        else -> "$committed $partial"
    }
}

/**
 * Two-tier sliding-window streaming on top of a non-streaming [SttEngine] (D5, D26).
 *
 * - Tier 1 (provisional): while speech is going on the current utterance window is decoded with [DecodeOpts.partial]
 *   (reduced encoder context, greedy) and shown as `partial`. Decodes never overlap or queue: the next one starts as
 *   soon as the previous finished and at least [adaptiveInterval] of new audio arrived (>= [Config.decodeIntervalSec],
 *   stretched to [Config.cadenceGapFactor] x the measured decode time so a slow model is not kept 100% busy).
 * - Tier 2 (final): when the VAD sees [Config.finalizeSilenceSec] of silence after speech (or the window reaches
 *   [Config.maxWindowSec], or [stop]) the utterance is queued for an accurate full-context re-decode (beam search when
 *   the measured speed allows). Its text replaces the provisional text of that utterance and becomes `committed`;
 *   committed text (last [Config.promptChars] chars) is the initial prompt of later decodes.
 * - Gate: windows without enough VAD speech are never decoded, and a partial is only decoded when new speech arrived
 *   since the previous decode (silence-only audio is what makes Whisper hallucinate "you"/"Thank you").
 * - Audio is buffered while the model is still loading ([modelLoading] is true); decoding starts when it is ready.
 * - After [Config.autoStopSilenceSec] without speech the session ends by itself ([autoStopped] becomes true).
 * - Audio time is measured in samples (deterministic); decode durations use the injected [clockMs].
 */
class StreamingTranscriber(
    private val engine: SttEngine,
    private val vad: Vad,
    private val config: Config = Config(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = { m -> try { DebugLog.i("Stream", m) } catch (_: Throwable) {} },
) {
    data class Config(
        val decodeIntervalSec: Float = 1.0f,
        val maxWindowSec: Float = 25f,
        val finalizeSilenceSec: Float = 0.8f,
        val autoStopSilenceSec: Float = 8f,
        val minSpeechSec: Float = 0.3f,
        val prerollSec: Float = 0.5f,
        val promptChars: Int = 224,
        /** Beam size of final decodes when decoding is fast enough (see [fastRtf]); otherwise greedy. */
        val finalBeam: Int = 3,
        /** Real-time factor (decode ms / audio ms) below which beam search is affordable for finals. */
        val fastRtf: Float = 0.5f,
        /** Idle gap between partial decodes as a fraction of the last partial decode time. */
        val cadenceGapFactor: Float = 0.5f,
        /** Keep at most this much trailing silence in an utterance sent to the final decode. */
        val finalTrailingSilenceSec: Float = 0.3f,
        /**
         * On stop, reuse the latest provisional text of the unfinished utterance instead of re-decoding it at full
         * context when at most [fastStopTailSec] of speech arrived since that partial decode (the main cause of the
         * lag after releasing the mic). If more speech is unprocessed, only that window is decoded, greedy and with
         * the reduced (fast) context.
         */
        val fastStop: Boolean = true,
        val fastStopTailSec: Float = 1.0f,
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
    private val _modelLoading = MutableStateFlow(false)
    /** True while audio is being buffered because the speech model is not ready yet. */
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()
    /** Milliseconds from [start] to the first non-empty text, -1 until then. */
    @Volatile var timeToFirstTextMs: Long = -1; private set
    /** Current adaptive gap between partial decodes in audio seconds (for tests/diagnostics). */
    @Volatile var adaptiveIntervalSec: Float = config.decodeIntervalSec; private set

    @Volatile private var partialRtf = -1f   // decode ms / audio ms of the latest partial decode
    @Volatile private var finalRtf = -1f     // ... of the latest final decode
    @Volatile private var stopRequested = false
    private var session: Job? = null
    private var collectJob: Job? = null
    private var startedAtMs = 0L

    private class Req(
        val pcm: FloatArray, val speechSec: Float, val utt: Int, val final: Boolean, val windowSec: Float,
        /** Final decode of the last window at stop: use the fast (partial) decode options. */
        val fast: Boolean = false,
    )

    /** Provisional text per utterance still waiting for its final decode; guarded by `lock`. */
    private val lock = Any()
    private val provisional = LinkedHashMap<Int, String>()
    private var committed = ""
    /** Utterance ids followed by a user-requested paragraph break. */
    private val breaks = HashSet<Int>()
    @Volatile private var breakRequested = false

    /** Appends the final [text] of utterance [utt] to [committed] (caller holds `lock`). */
    private fun commit(utt: Int, text: String) {
        if (text.isNotEmpty()) {
            committed = when {
                committed.isEmpty() -> text
                committed.endsWith("\n") -> committed + text
                else -> "$committed $text"
            }
        }
        if (utt in breaks && committed.isNotEmpty() && !committed.endsWith("\n")) committed += "\n\n"
    }

    /** Provisional texts in utterance order, joined with a paragraph break after the utterances that have one. */
    private fun provisionalText(): String {
        val sb = StringBuilder()
        var pendingBreak = false
        for ((u, t) in provisional) {
            if (t.isBlank()) { if (u in breaks) pendingBreak = true; continue }
            if (sb.isNotEmpty()) sb.append(if (pendingBreak) "\n\n" else " ")
            sb.append(t)
            pendingBreak = u in breaks
        }
        return sb.toString()
    }

    /**
     * Ends the current paragraph: finalizes the utterance in progress and puts a blank line after it. Takes effect on
     * the next audio chunk (~100 ms). Ignored while nothing has been dictated yet.
     */
    fun insertBreak() { breakRequested = true }

    private fun publish() {
        val t = synchronized(lock) { Transcript(committed, provisionalText()) }
        _transcript.value = t
        if (timeToFirstTextMs < 0 && t.full.isNotBlank()) {
            timeToFirstTextMs = clockMs() - startedAtMs
            log("time-to-first-text: ${timeToFirstTextMs} ms after start")
        }
    }

    /** Starts a session; returns immediately. [audio] is collected until it ends, [stop] is called or auto-stop fires. */
    fun start(scope: CoroutineScope, audio: Flow<FloatArray>): Job {
        check(session?.isActive != true) { "already running" }
        stopRequested = false
        synchronized(lock) { provisional.clear(); committed = ""; breaks.clear() }
        breakRequested = false
        _transcript.value = Transcript()
        _autoStopped.value = false
        _error.value = null
        timeToFirstTextMs = -1
        startedAtMs = clockMs()
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
        val currentUtt = AtomicInteger(0)
        val worker = scope.launch(dispatcher) {
            var ready = false
            for (r in queue) {
                try {
                    if (!ready) {
                        _modelLoading.value = true
                        try { engine.awaitReady() } finally { _modelLoading.value = false }
                        ready = true
                    }
                    // stale provisional request (its utterance already has/will get a final): skip
                    if (!r.final && r.utt != currentUtt.get()) continue
                    val prompt = synchronized(lock) { committed }.takeLast(config.promptChars).trim().ifEmpty { null }
                    // full-context finals cost roughly 3x a reduced-context partial when no final was measured yet
                    val estRtf = if (finalRtf >= 0f) finalRtf else if (partialRtf >= 0f) partialRtf * 3f else -1f
                    val beam = if (!r.final || r.fast) 1 else if (estRtf in 0f..config.fastRtf) config.finalBeam else 1
                    val opts = DecodeOpts(partial = !r.final || r.fast, beam = beam)
                    val t0 = clockMs()
                    val raw = withContext(dispatcher) { engine.transcribe(r.pcm, prompt, opts) }
                    val ms = clockMs() - t0
                    if (r.windowSec > 0.2f) {
                        val rtf = ms / (r.windowSec * 1000f)
                        if (r.final) finalRtf = rtf else partialRtf = rtf
                    }
                    val text = HallucinationFilter.clean(raw, r.speechSec, speechRatio = r.speechSec / r.windowSec.coerceAtLeast(0.01f))
                    if (r.final) {
                        log("final utt#${r.utt} window=${"%.1f".format(r.windowSec)}s speech=${"%.1f".format(r.speechSec)}s beam=$beam ms=$ms")
                        synchronized(lock) {
                            provisional.remove(r.utt)
                            commit(r.utt, text)
                        }
                    } else {
                        adaptiveIntervalSec = maxOf(config.decodeIntervalSec, ms / 1000f * config.cadenceGapFactor)
                        synchronized(lock) {
                            // a final of this utterance may already be queued; provisional is only shown until it lands
                            if (r.utt == currentUtt.get() || provisional.containsKey(r.utt)) provisional[r.utt] = text
                        }
                    }
                    publish()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _error.value = e
                    if (r.final) synchronized(lock) {  // keep what the user already saw instead of losing it
                        val p = provisional.remove(r.utt).orEmpty()
                        commit(r.utt, p.trim())
                    }
                } finally {
                    pending.decrementAndGet()
                }
            }
        }

        // window state
        var window = FloatArray(SAMPLE_RATE * 30)
        var len = 0
        var speechSamples = 0
        var speechAtLastDecode = 0
        var trailingSilence = 0       // samples of silence since last speech frame (within window)
        var sinceDecode = 0
        var sinceSpeechTotal = 0      // samples since last speech anywhere (for auto-stop)
        var smooth = 0f
        val finalTrailing = (config.finalTrailingSilenceSec * SAMPLE_RATE).toInt()

        fun submit(final: Boolean, fast: Boolean = false) {
            var n = len
            if (final && trailingSilence > finalTrailing) n = (len - (trailingSilence - finalTrailing)).coerceAtLeast(1)
            val pcm = window.copyOf(n)
            pending.incrementAndGet()
            speechAtLastDecode = speechSamples
            queue.trySend(Req(pcm, speechSamples.toFloat() / SAMPLE_RATE, currentUtt.get(), final, n.toFloat() / SAMPLE_RATE, fast))
        }
        fun resetWindow() {
            len = 0; speechSamples = 0; speechAtLastDecode = 0; trailingSilence = 0; sinceDecode = 0
            currentUtt.incrementAndGet()
        }
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

                    val forceBreak = breakRequested.also { breakRequested = false }
                    if (forceBreak && speechSamples < minSpeechSamples) {
                        // nothing in the current window: the paragraph ends after the previous utterance
                        val prev = currentUtt.get() - 1
                        synchronized(lock) {
                            if (prev >= 0) {
                                breaks.add(prev)
                                if (!provisional.containsKey(prev) && committed.isNotEmpty() && !committed.endsWith("\n")) committed += "\n\n"
                            }
                        }
                        publish()
                    }

                    if (speechSamples < minSpeechSamples) {
                        // gate: nothing worth decoding yet, keep only a short pre-roll
                        if (len > prerollSamples + chunk.size) {
                            val drop = len - prerollSamples
                            window.copyInto(window, 0, drop, len)
                            len -= drop
                        }
                        sinceDecode = 0
                    } else if (forceBreak || trailingSilence >= finalizeSamples || len >= maxSamples) {
                        val utt = currentUtt.get()
                        if (forceBreak) synchronized(lock) { breaks.add(utt) }
                        submit(true)
                        resetWindow()
                        // keep the finished utterance's provisional text on screen until its final replaces it
                        synchronized(lock) { if (!provisional.containsKey(utt)) provisional[utt] = "" }
                    } else {
                        val intervalSamples = (adaptiveIntervalSec * SAMPLE_RATE).toInt()
                        // never queue: only when idle; only when new speech arrived since the last decode (gate)
                        if (sinceDecode >= intervalSamples && pending.get() == 0 && speechSamples > speechAtLastDecode) {
                            sinceDecode = 0
                            submit(false)
                        }
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
            if (speechSamples >= minSpeechSamples && len > 0) {
                val utt = currentUtt.get()
                val shown = synchronized(lock) { provisional[utt] }
                val tailSec = (speechSamples - speechAtLastDecode).toFloat() / SAMPLE_RATE
                if (config.fastStop && !shown.isNullOrBlank() && tailSec <= config.fastStopTailSec) {
                    // the partial already covers (almost) everything: promote it, no re-decode
                    log("fast stop: reusing provisional text of utt#$utt (tail=${"%.2f".format(tailSec)}s)")
                    synchronized(lock) { provisional.remove(utt); commit(utt, shown.trim()) }
                } else {
                    submit(true, fast = config.fastStop)
                }
            }
        } finally {
            queue.close()
            worker.join()
            _level.value = 0f
            synchronized(lock) { provisional.clear() }  // anything still provisional had no final: drop it
            publish()
        }
    }
}
