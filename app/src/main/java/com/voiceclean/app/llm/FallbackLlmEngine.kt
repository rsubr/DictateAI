package com.voiceclean.app.llm

/**
 * Wraps a [primary] engine; until it loads a model successfully (no model configured, load failed,
 * native lib missing, unsupported backend) all calls go to [fallback] so the UI keeps working.
 */
class FallbackLlmEngine(private val primary: LlmEngine, private val fallback: LlmEngine) : LlmEngine {
    @Volatile private var usePrimary = false
    @Volatile var lastError: Throwable? = null; private set
    val isReal: Boolean get() = usePrimary
    override val id: String get() = if (usePrimary) primary.id else fallback.id

    override suspend fun load(path: String, cfg: LlmConfig) {
        usePrimary = false
        try {
            primary.load(path, cfg)
            usePrimary = true
            lastError = null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = e
            fallback.load(path, cfg)
        }
    }

    override fun generate(prompt: String, params: GenParams) =
        (if (usePrimary) primary else fallback).generate(prompt, params)

    override fun unload() { usePrimary = false; primary.unload(); fallback.unload() }
}
