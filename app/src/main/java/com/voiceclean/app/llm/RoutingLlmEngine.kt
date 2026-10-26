package com.voiceclean.app.llm

import kotlinx.coroutines.flow.Flow

/** Chooses the backend by model file type: `.pte` -> ExecuTorch-QNN, anything else (GGUF) -> llama.cpp. */
class RoutingLlmEngine(private val llama: LlmEngine, private val qnn: LlmEngine) : LlmEngine {
    @Volatile private var current: LlmEngine = llama
    override val id: String get() = current.id

    override suspend fun load(path: String, cfg: LlmConfig) {
        val next = if (path.endsWith(".pte", ignoreCase = true)) qnn else llama
        if (next !== current) current.unload()
        current = next
        next.load(path, cfg)
    }

    override fun generate(prompt: String, params: GenParams): Flow<String> = current.generate(prompt, params)
    override fun unload() { llama.unload(); qnn.unload() }
}
