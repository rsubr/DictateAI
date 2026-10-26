package com.voiceclean.app.di

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Seam between the UI/pipeline (Home) and whoever owns real engines + active models (Native/Models agents).
 * The default [NoopEngineProvider] keeps the fake engines usable. A real implementation should:
 *  - report availability from the active model settings + files on disk,
 *  - load the engines lazily in [ensureSttLoaded]/[ensureLlmLoaded] (idempotent, throws with a user-readable message).
 */
interface EngineProvider {
    val sttAvailable: Flow<Boolean>
    val llmAvailable: Flow<Boolean>
    /** Active LLM file/id, used to pick the prompt template family (e.g. "Qwen3-4B-Q5_K_M.gguf"). */
    fun llmModelName(): String
    suspend fun ensureSttLoaded()
    suspend fun ensureLlmLoaded()
}

object NoopEngineProvider : EngineProvider {
    override val sttAvailable: Flow<Boolean> = flowOf(true)
    override val llmAvailable: Flow<Boolean> = flowOf(true)
    override fun llmModelName() = "fake"
    override suspend fun ensureSttLoaded() {}
    override suspend fun ensureLlmLoaded() {}
}
