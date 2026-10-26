package com.voiceclean.app.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class LlmConfig(val threads: Int = 4, val contextSize: Int = 4096)
data class GenParams(val temperature: Float = 0.2f, val maxTokens: Int = 512)

interface LlmEngine {
    val id: String
    suspend fun load(path: String, cfg: LlmConfig)
    fun generate(prompt: String, params: GenParams): Flow<String>
    fun unload()
}

/** Placeholder engine: streams a trivially "cleaned" version of the input. */
class FakeLlmEngine : LlmEngine {
    override val id = "fake"
    override suspend fun load(path: String, cfg: LlmConfig) { delay(50) }
    override fun generate(prompt: String, params: GenParams): Flow<String> = flow {
        // Fake: the prompt is "<STYLE>\n<text>"; see FakeCleanup in Home.
        val text = prompt.substringAfter('\n', prompt)
        for (w in text.split(' ')) { emit("$w "); delay(20) }
    }
    override fun unload() {}
}
