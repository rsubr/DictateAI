package com.voiceclean.app.stt

import kotlinx.coroutines.delay

interface SttEngine {
    suspend fun load(path: String)
    fun transcribe(pcm: FloatArray, prompt: String?): String
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
