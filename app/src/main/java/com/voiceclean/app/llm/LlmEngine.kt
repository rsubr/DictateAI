package com.voiceclean.app.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class LlmConfig(val threads: Int = 4, val contextSize: Int = 4096)
/**
 * Sampling/generation params. [systemPrompt] (optional) becomes the system message and `prompt`
 * the user message when applying the model's chat template; with null, `prompt` alone is the user message.
 */
data class GenParams(
    val temperature: Float = 0.2f,
    val maxTokens: Int = 512,
    val systemPrompt: String? = null,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
)

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
        val text = Regex("(?s)<text>\\s*(.*?)\\s*</text>").findAll(prompt).lastOrNull()?.groupValues?.get(1) ?: prompt.substringAfter('\n', prompt)
        for (w in text.split(' ')) { emit("$w "); delay(20) }
    }
    override fun unload() {}
}
