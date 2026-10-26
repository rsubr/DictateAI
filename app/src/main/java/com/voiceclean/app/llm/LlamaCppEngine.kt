package com.voiceclean.app.llm

import android.content.Context
import com.voiceclean.app.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import java.io.File

/** llama.cpp-backed [LlmEngine]. Streams UTF-8-safe chunks; cancelling the collector stops native generation. */
class LlamaCppEngine(context: Context) : LlmEngine {
    override val id = "llama.cpp"
    private val nativeDir = context.applicationInfo.nativeLibraryDir
    @Volatile private var handle = 0L
    val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(path: String, cfg: LlmConfig) = withContext(Dispatchers.IO) {
        check(NativeBridge.isLoaded) { "native library not loaded" }
        require(File(path).isFile) { "LLM model not found: $path" }
        unload()
        NativeBridge.initBackends(nativeDir)
        handle = NativeBridge.llamaLoad(path, cfg.contextSize, cfg.threads)
    }

    override fun generate(prompt: String, params: GenParams): Flow<String> = callbackFlow {
        val h = handle
        check(h != 0L) { "LLM model not loaded" }
        val job = launch(Dispatchers.Default) {
            try {
                NativeBridge.llamaGenerate(
                    h, params.systemPrompt ?: "", prompt, params.maxTokens, params.temperature,
                    params.topP, params.topK, params.minP, params.repeatPenalty,
                ) { piece -> isActive && trySendBlocking(piece).isSuccess }
                close()
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose {
            // Collector gone (cancelled/finished): stop native decoding promptly.
            if (job.isActive) NativeBridge.llamaCancel(h)
        }
    }.buffer(Channel.UNLIMITED)

    @Synchronized override fun unload() {
        val h = handle
        handle = 0L
        if (h != 0L) { NativeBridge.llamaCancel(h); NativeBridge.llamaFree(h) }
    }
}
