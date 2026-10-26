package com.voiceclean.app.diag

import java.io.File

/** Heuristics for a sensible default thread count (performance cores only; little cores slow ggml down). */
object CpuInfo {
    /** Max frequency (kHz) per core from sysfs; empty if unreadable. */
    fun maxFreqsKhz(): List<Long> = try {
        (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                ?.readText()?.trim()?.toLongOrNull()
        }
    } catch (_: Throwable) { emptyList() }

    /** Number of "big" cores: those running at >= 70% of the fastest core's max frequency. */
    fun performanceCores(freqs: List<Long> = maxFreqsKhz(), total: Int = Runtime.getRuntime().availableProcessors()): Int {
        if (freqs.size >= 4) {
            val max = freqs.max()
            return freqs.count { it >= max * 0.7 }
        }
        return (total / 2).coerceAtLeast(1)
    }

    /** Default threads for whisper/llama: performance-core count clamped to 2..5 (typically 4 on Snapdragon 8). */
    fun defaultThreads(): Int = cached
    private val cached: Int by lazy { performanceCores().coerceIn(2, 5) }

    fun describe(): String {
        val f = maxFreqsKhz()
        return if (f.isEmpty()) "cores=${Runtime.getRuntime().availableProcessors()} (freqs unreadable)"
        else "cores=${f.size} maxMHz=" + f.joinToString(",") { (it / 1000).toString() }
    }
}
