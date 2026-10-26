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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** llama.cpp-backed [LlmEngine]. Streams UTF-8-safe chunks; cancelling the collector stops native generation. */
class LlamaCppEngine(context: Context) : LlmEngine {
    override val id = "llama.cpp"
    private val nativeDir = context.applicationInfo.nativeLibraryDir
    @Volatile private var handle = 0L
    // generate() holds the read lock for the whole native call; unload() cancels, then waits for the write lock.
    private val lock = ReentrantReadWriteLock()
    val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(path: String, cfg: LlmConfig) = withContext(Dispatchers.IO) {
        check(NativeBridge.isLoaded) { "native library not loaded" }
        require(File(path).isFile) { "LLM model not found: $path" }
        unload()
        NativeBridge.initBackends(nativeDir)
        val h = NativeBridge.llamaLoad(path, cfg.contextSize, cfg.threads)
        lock.write { handle = h }
    }

    override fun generate(prompt: String, params: GenParams): Flow<String> = callbackFlow {
        val h = handle
        check(h != 0L) { "LLM model not loaded" }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            try {
                lock.read {
                    check(handle == h) { "LLM model was unloaded" }
                    NativeBridge.llamaGenerate(
                        h, params.systemPrompt ?: "", prompt, params.maxTokens, params.temperature,
                        params.topP, params.topK, params.minP, params.repeatPenalty,
                    ) { piece -> isActive && trySendBlocking(piece).isSuccess }
                }
                finished.set(true)
                close()
            } catch (e: Throwable) {
                finished.set(true)
                close(e)
            }
        }
        awaitClose {
            // Collector gone before the native call ended: stop native decoding promptly.
            if (!finished.get() && job.isActive) NativeBridge.llamaCancel(h)
        }
    }.buffer(Channel.UNLIMITED)

    override fun unload() {
        val h = handle
        if (h != 0L) NativeBridge.llamaCancel(h)  // makes a running generate return quickly
        lock.write {
            val cur = handle
            handle = 0L
            if (cur != 0L) NativeBridge.llamaFree(cur)
        }
    }
}
