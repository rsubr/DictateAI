package com.voiceclean.app.stt

import kotlinx.coroutines.delay

/**
 * Per-call decode hints. [partial] = provisional text while the user is still speaking (fast, reduced encoder context,
 * greedy, no temperature fallback); otherwise a final, full-accuracy decode of a finished utterance.
 * [beam] 0 = engine default.
 */
data class DecodeOpts(val partial: Boolean = false, val beam: Int = 0)

interface SttEngine {
    suspend fun load(path: String)
    fun transcribe(pcm: FloatArray, prompt: String?): String
    fun transcribe(pcm: FloatArray, prompt: String?, opts: DecodeOpts): String = transcribe(pcm, prompt)
    /** Suspends until the model is loaded (loading it if necessary); throws if loading fails. Default: always ready. */
    suspend fun awaitReady() {}
    fun unload()
}

/** Placeholder engine so the UI works without native libs. */
class FakeSttEngine : SttEngine {
    private var n = 0
    private val phrases = listOf(
        "um so i wanted to uh send you the report tomorrow",
        "can u check the the numbers before friday",
        "lets meet at three pm or maybe four",
    )
    override suspend fun load(path: String) { delay(50) }
    override fun transcribe(pcm: FloatArray, prompt: String?): String = phrases[n++ % phrases.size]
    override fun unload() {}
}
