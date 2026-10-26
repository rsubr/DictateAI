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
}
