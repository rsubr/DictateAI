package com.voiceclean.app.audio

import kotlin.math.sqrt

/** Voice activity detector. Silero (or similar) can implement this later. Frames may be any length. */
interface Vad {
    /** Feed the next frame of 16 kHz mono audio; returns true if speech is considered active (incl. hangover). */
    fun process(frame: FloatArray): Boolean
    fun reset()
}

fun rms(frame: FloatArray): Float {
    if (frame.isEmpty()) return 0f
    var s = 0.0
    for (x in frame) s += x * x
    return sqrt(s / frame.size).toFloat()
}

/**
 * Energy VAD with an adaptive noise floor.
 * threshold = max(minThreshold, noiseFloor * ratio). Speech stays "active" for [hangoverMs] after the last loud frame.
 * The noise floor falls quickly and rises slowly, and is only updated on non-speech frames.
 */
class EnergyVad(
    private val minThreshold: Float = 0.012f,
    private val ratio: Float = 3.0f,
    private val hangoverMs: Int = 300,
    private val sampleRate: Int = SAMPLE_RATE,
    initialFloor: Float = 0.004f,
) : Vad {
    var noiseFloor = initialFloor
        private set
    private val initial = initialFloor
    private var hangLeftMs = 0.0

    val threshold: Float get() = maxOf(minThreshold, noiseFloor * ratio)

    override fun process(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return hangLeftMs > 0
        val level = rms(frame)
        val ms = frame.size * 1000.0 / sampleRate
        if (level > threshold) {
            hangLeftMs = hangoverMs.toDouble()
            return true
        }
        // non-speech: adapt noise floor (fast down, slow up)
        noiseFloor = if (level < noiseFloor) noiseFloor * 0.8f + level * 0.2f else noiseFloor * 0.98f + level * 0.02f
        if (hangLeftMs > 0) {
            hangLeftMs -= ms
            return true
        }
        return false
    }

    override fun reset() {
        noiseFloor = initial
        hangLeftMs = 0.0
    }
}
