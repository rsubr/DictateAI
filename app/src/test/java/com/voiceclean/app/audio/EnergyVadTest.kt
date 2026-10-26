package com.voiceclean.app.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EnergyVadTest {
    private fun tone(ms: Int, amp: Float) = FloatArray(16 * ms) { amp * sin(2 * PI * 220 * it / 16000).toFloat() }
    private fun silence(ms: Int) = FloatArray(16 * ms)

    @Test fun silenceIsNotSpeech() {
        val v = EnergyVad()
        repeat(20) { assertFalse(v.process(silence(100))) }
    }

    @Test fun speechDetectedThenHangover() {
        val v = EnergyVad(hangoverMs = 300)
        assertTrue(v.process(tone(100, 0.3f)))
        assertTrue(v.process(silence(100))) // hangover
        assertTrue(v.process(silence(100)))
        assertTrue(v.process(silence(100)))
        assertFalse(v.process(silence(100)))
    }

    @Test fun adaptsToNoiseFloor() {
        val v = EnergyVad()
        val lowNoise = tone(100, 0.012f) // rms ~0.0085, below the minimum threshold
        repeat(100) { assertFalse(v.process(lowNoise)) }
        assertTrue(v.noiseFloor > 0.007f)
        assertTrue(v.threshold > 0.02f)
        // a moderately louder sound that a fresh VAD calls speech is now ignored, real speech still passes
        val mid = tone(100, 0.03f)
        assertTrue(EnergyVad().process(mid))
        assertFalse(v.process(mid))
        assertTrue(v.process(tone(100, 0.4f)))
    }

    @Test fun rmsOfTone() {
        assertEquals(0.3f / Math.sqrt(2.0).toFloat(), rms(tone(100, 0.3f)), 0.01f)
    }
}
