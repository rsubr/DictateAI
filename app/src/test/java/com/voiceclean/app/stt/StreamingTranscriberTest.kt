package com.voiceclean.app.stt

import com.voiceclean.app.audio.EnergyVad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingTranscriberTest {
    private class FakeEngine(val replies: (Int, FloatArray) -> String) : SttEngine {
        val calls = mutableListOf<Pair<Int, String?>>()
        var concurrent = 0; var maxConcurrent = 0
        override suspend fun load(path: String) {}
        override fun transcribe(pcm: FloatArray, prompt: String?): String {
            concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent)
            calls += pcm.size to prompt
            val r = replies(calls.size, pcm)
            concurrent--
            return r
        }
        override fun unload() {}
    }

    private fun tone(ms: Int) = FloatArray(16 * ms) { 0.3f * sin(2 * PI * 220 * it / 16000).toFloat() }
    private fun quiet(ms: Int) = FloatArray(16 * ms)

    /** 100 ms chunks: [speechMs] of tone then [silenceMs] of silence, with a tiny delay per chunk. */
    private fun audio(vararg segs: Pair<Boolean, Int>): Flow<FloatArray> = flow {
        for ((speech, ms) in segs) repeat(ms / 100) { emit(if (speech) tone(100) else quiet(100)); delay(100) }
    }

    @Test fun commitsUtteranceOnSilenceAndCarriesPrompt() = runTest {
        val engine = FakeEngine { n, pcm -> if (pcm.size > 16000 * 2.5) "first sentence" else "second sentence" }
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d)
        val job = t.start(this, audio(true to 3000, false to 1500, true to 1000, false to 1500))
        job.join()
        assertEquals("first sentence second sentence", t.transcript.value.committed)
        assertEquals("", t.transcript.value.partial)
        assertTrue(engine.calls.any { it.second == "first sentence" }) // context carried into the next window
        assertEquals(1, engine.maxConcurrent)
    }

    @Test fun partialsEmittedDuringSpeech() = runTest {
        val engine = FakeEngine { n, _ -> "partial$n" }
        val seen = mutableSetOf<String>()
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d)
        val job = t.start(this, audio(true to 4000))
        val watcher = launchWatcher(t, seen)
        job.join(); watcher.cancel()
        assertTrue("partials: $seen", seen.any { it.startsWith("partial") })
    }

    private fun TestScope.launchWatcher(t: StreamingTranscriber, seen: MutableSet<String>) =
        backgroundScope.let { s -> s.launchCollect(t, seen) }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(t: StreamingTranscriber, seen: MutableSet<String>) =
        this.launch { t.transcript.collect { if (it.partial.isNotEmpty()) seen += it.partial } }

    @Test fun stopFlushesFinalDecode() = runTest {
        val engine = FakeEngine { _, _ -> "flushed text" }
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d)
        t.start(this, audio(true to 60_000))
        delay(2_050)
        val text = t.stop()
        assertEquals("flushed text", text)
    }

    @Test fun autoStopsAfterLongSilence() = runTest {
        val engine = FakeEngine { _, _ -> "hello" }
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), StreamingTranscriber.Config(autoStopSilenceSec = 3f), d)
        val job = t.start(this, audio(true to 1500, false to 30_000))
        job.join()
        assertTrue(t.autoStopped.value)
        assertEquals("hello", t.transcript.value.committed)
    }

    @Test fun silenceNeverReachesEngineAndHallucinationsDropped() = runTest {
        val engine = FakeEngine { _, _ -> "[BLANK_AUDIO]" }
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d)
        t.start(this, audio(false to 5000)).join()
        assertTrue(engine.calls.isEmpty())
        // speech but model hallucinates a tag: nothing committed
        val e2 = FakeEngine { _, _ -> "[BLANK_AUDIO]" }
        val t2 = StreamingTranscriber(e2, EnergyVad(), dispatcher = d)
        t2.start(this, audio(true to 1000, false to 1500)).join()
        assertEquals("", t2.transcript.value.committed)
    }

    @Test fun windowCapForcesCommit() = runTest {
        val engine = FakeEngine { _, pcm -> "chunk${pcm.size / 16000}" }
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), StreamingTranscriber.Config(maxWindowSec = 3f), d)
        t.start(this, audio(true to 7000)).join()
        assertTrue(engine.calls.any { it.first <= 16000 * 3 + 1600 })
        assertTrue(t.transcript.value.committed.contains("chunk"))
        advanceUntilIdle()
    }

    /** Engine that records DecodeOpts and advances a fake clock by [costMs] per decode. */
    private class TierEngine(
        val partialText: String = "provisional words", val finalText: String = "final words",
        val costMs: (DecodeOpts) -> Long = { 100 },
    ) : SttEngine {
        var now = 0L
        val calls = mutableListOf<DecodeOpts>()
        val sizes = mutableListOf<Int>()
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var readyChecks = 0
        override suspend fun load(path: String) {}
        override suspend fun awaitReady() { readyChecks++; gate?.await() }
        override fun transcribe(pcm: FloatArray, prompt: String?) = transcribe(pcm, prompt, DecodeOpts())
        override fun transcribe(pcm: FloatArray, prompt: String?, opts: DecodeOpts): String {
            calls += opts; sizes += pcm.size; now += costMs(opts)
            return if (opts.partial) partialText else finalText
        }
        override fun unload() {}
    }

    @Test fun twoTierProvisionalIsReplacedByFinal() = runTest {
        val engine = TierEngine()
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d, clockMs = { engine.now })
        val seen = mutableListOf<Transcript>()
        val w = backgroundScope.launch { t.transcript.collect { seen += it } }
        t.start(this, audio(true to 3000, false to 1500)).join()
        w.cancel()
        assertTrue("provisional shown: $seen", seen.any { it.partial == "provisional words" && it.committed.isEmpty() })
        assertEquals(Transcript("final words", ""), t.transcript.value)
        assertTrue(engine.calls.first().partial)
        assertFalse(engine.calls.last().partial)
        assertEquals(1, engine.calls.count { !it.partial })
    }

    @Test fun silenceOnlyAndTrailingSilenceAreGated() = runTest {
        val engine = TierEngine()
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d, clockMs = { engine.now })
        t.start(this, audio(false to 6000)).join()
        assertTrue(engine.calls.isEmpty())
        // 1.5 s speech then long silence: partial decodes stop with the speech, exactly one final
        val e2 = TierEngine()
        val t2 = StreamingTranscriber(e2, EnergyVad(), dispatcher = d, clockMs = { e2.now })
        t2.start(this, audio(true to 1500, false to 6000)).join()
        assertEquals(1, e2.calls.count { !it.partial })
        assertTrue("partials=${e2.calls.count { it.partial }}", e2.calls.count { it.partial } <= 2)
        // the final audio is trimmed (not 7.5 s of mostly silence)
        assertTrue(e2.sizes.last() < 16000 * 3)
    }

    @Test fun cadenceAdaptsToDecodeTimeAndNeverOverlaps() = runTest {
        val engine = TierEngine(costMs = { if (it.partial) 4000 else 500 })
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d, clockMs = { engine.now })
        t.start(this, audio(true to 12_000)).join()
        assertEquals(2.0f, t.adaptiveIntervalSec, 0.001f)  // 4 s decode * gap factor 0.5
        val partials = engine.calls.count { it.partial }
        assertTrue("partials=$partials", partials in 1..6)  // 12 s of audio, >= 2 s apart, never queued
    }

    @Test fun finalUsesBeamOnlyWhenFast() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val fast = TierEngine(costMs = { 100 })
        StreamingTranscriber(fast, EnergyVad(), dispatcher = d, clockMs = { fast.now })
            .also { it.start(this, audio(true to 3000, false to 1500)).join() }
        assertEquals(3, fast.calls.last { !it.partial }.beam)
        val slow = TierEngine(costMs = { 5000 })
        StreamingTranscriber(slow, EnergyVad(), dispatcher = d, clockMs = { slow.now })
            .also { it.start(this, audio(true to 3000, false to 1500)).join() }
        assertEquals(1, slow.calls.last { !it.partial }.beam)
    }

    @Test fun audioBufferedWhileModelLoads() = runTest {
        val engine = TierEngine()
        engine.gate = kotlinx.coroutines.CompletableDeferred()
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d, clockMs = { engine.now })
        val job = t.start(this, audio(true to 2000, false to 1500))
        delay(3_000)
        assertTrue(t.modelLoading.value)
        assertTrue(engine.calls.isEmpty())
        engine.gate!!.complete(Unit)
        job.join()
        assertFalse(t.modelLoading.value)
        assertEquals("final words", t.transcript.value.committed)
        assertEquals(1, engine.readyChecks)
        assertTrue(engine.sizes.last() >= 16000 * 2)  // the audio spoken during loading was kept
    }

    @Test fun logsTimeToFirstText() = runTest {
        val engine = TierEngine()
        val logs = mutableListOf<String>()
        val d = StandardTestDispatcher(testScheduler)
        val t = StreamingTranscriber(engine, EnergyVad(), dispatcher = d, clockMs = { engine.now }, log = { logs += it })
        t.start(this, audio(true to 3000, false to 1500)).join()
        assertTrue(t.timeToFirstTextMs >= 0)
        assertTrue(logs.any { it.startsWith("time-to-first-text") })
    }
}
