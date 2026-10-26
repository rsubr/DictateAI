package com.voiceclean.app.di

import android.app.ActivityManager
import android.content.Context
import com.voiceclean.app.data.SettingsRepository
import com.voiceclean.app.diag.DebugLog
import com.voiceclean.app.llm.FallbackLlmEngine
import com.voiceclean.app.llm.LlmConfig
import com.voiceclean.app.llm.LlmEngine
import com.voiceclean.app.models.ModelRepository
import com.voiceclean.app.stt.FallbackSttEngine
import com.voiceclean.app.stt.SttEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class EngineStatus { NotConfigured, Idle, Loading, Ready, Error }

data class EngineState(
    val status: EngineStatus = EngineStatus.Idle,
    val message: String? = null,
    val modelFile: String? = null,
    val loadMs: Long = 0,
)

/**
 * Seam between the UI/pipeline (Home) and whoever owns real engines + active models.
 * [RealEngineProvider] is the production implementation; [NoopEngineProvider] keeps fakes usable (tests/previews).
 */
interface EngineProvider {
    val sttAvailable: Flow<Boolean>
    val llmAvailable: Flow<Boolean>
    val sttState: StateFlow<EngineState>
    val llmState: StateFlow<EngineState>
    /** Active LLM file name, used to pick the prompt family (e.g. "Qwen3-4B-Q5_K_M.gguf"). */
    fun llmModelName(): String
    /** Idempotent; loads (or reloads after a selection/settings change) and throws with a user-readable message. */
    suspend fun ensureSttLoaded()
    suspend fun ensureLlmLoaded()
    /** Fire-and-forget background loads (errors land in [sttState]/[llmState]). */
    fun warmUpStt() {}
    fun warmUpLlm() {}
    /** Drops the loaded speech model and loads it again (e.g. after the flash-attention setting or benchmark changed). */
    suspend fun reloadStt() { ensureSttLoaded() }
    /** Schedule releasing the speech model's memory if it is not used again for a while. */
    fun releaseSttWhenIdle() {}
}

object NoopEngineProvider : EngineProvider {
    override val sttAvailable: Flow<Boolean> = flowOf(true)
    override val llmAvailable: Flow<Boolean> = flowOf(true)
    override val sttState: StateFlow<EngineState> = MutableStateFlow(EngineState(EngineStatus.Ready))
    override val llmState: StateFlow<EngineState> = MutableStateFlow(EngineState(EngineStatus.Ready))
    override fun llmModelName() = "fake"
    override suspend fun ensureSttLoaded() {}
    override suspend fun ensureLlmLoaded() {}
}

/**
 * Loads whisper / llama.cpp lazily on the active model paths from [ModelRepository].
 *
 * - All loads run under one mutex on Dispatchers.IO (never the main thread), so two big models are never loading at once.
 * - A change of the active selection (or deletion) unloads the stale model immediately (no two copies resident);
 *   the new one loads lazily. Changed thread count / context size reload the LLM on next use.
 * - A failed native load is surfaced as [EngineStatus.Error] and thrown; we never silently run the fake engines.
 * - On devices with < 7.5 GB RAM only one of the two models stays resident; otherwise both do, and the
 *   speech model is released after [idleReleaseMs] without use.
 */
class RealEngineProvider(
    context: Context,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val stt: SttEngine,
    private val llm: LlmEngine,
    private val scope: CoroutineScope,
    private val idleReleaseMs: Long = 5 * 60_000L,
) : EngineProvider {

    private val loadMutex = Mutex()
    private val _sttState = MutableStateFlow(EngineState())
    private val _llmState = MutableStateFlow(EngineState())
    override val sttState: StateFlow<EngineState> = _sttState.asStateFlow()
    override val llmState: StateFlow<EngineState> = _llmState.asStateFlow()

    override val sttAvailable: Flow<Boolean> = models.activeSttPath().map { it != null }.distinctUntilChanged()
    override val llmAvailable: Flow<Boolean> = models.activeLlmPath().map { it != null }.distinctUntilChanged()

    private data class LlmKey(val path: String, val threads: Int, val ctx: Int)
    private var loadedStt: String? = null
    private var loadedSttFlash: String = ""
    private var loadedLlm: LlmKey? = null
    @Volatile private var llmName: String = ""
    private var idleJob: Job? = null

    private val lowRam: Boolean = run {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem < 7_500_000_000L
    }

    init {
        scope.launch {
            models.activeSttPath().collect { p ->
                loadMutex.withLock {
                    if (p == null) _sttState.value = EngineState(EngineStatus.NotConfigured, "No speech model selected")
                    if (loadedStt != null && loadedStt != p) unloadStt("selection changed")
                    if (p != null && loadedStt == null && _sttState.value.status != EngineStatus.Loading)
                        _sttState.value = EngineState(EngineStatus.Idle, modelFile = File(p).name)
                }
            }
        }
        scope.launch {
            models.activeLlmPath().collect { p ->
                if (p != null) llmName = File(p).name
                loadMutex.withLock {
                    if (p == null) { llmName = ""; _llmState.value = EngineState(EngineStatus.NotConfigured, "No language model selected") }
                    if (loadedLlm != null && loadedLlm?.path != p) unloadLlm("selection changed")
                    if (p != null && loadedLlm == null && _llmState.value.status != EngineStatus.Loading)
                        _llmState.value = EngineState(EngineStatus.Idle, modelFile = File(p).name)
                }
            }
        }
    }

    override fun llmModelName(): String = llmName

    private suspend fun unloadStt(why: String) {
        withContext(Dispatchers.IO) { runCatching { stt.unload() } }
        if (loadedStt != null) DebugLog.i("Engines", "speech model unloaded ($why)")
        loadedStt = null
        if (_sttState.value.status != EngineStatus.NotConfigured) _sttState.value = EngineState(EngineStatus.Idle)
    }

    private suspend fun unloadLlm(why: String) {
        withContext(Dispatchers.IO) { runCatching { llm.unload() } }
        if (loadedLlm != null) DebugLog.i("Engines", "language model unloaded ($why)")
        loadedLlm = null
        if (_llmState.value.status != EngineStatus.NotConfigured) _llmState.value = EngineState(EngineStatus.Idle)
    }

    private fun isReal(e: Any): Boolean = when (e) {
        is FallbackSttEngine -> e.isReal
        is FallbackLlmEngine -> e.isReal
        else -> true
    }

    private fun lastError(e: Any): Throwable? = when (e) {
        is FallbackSttEngine -> e.lastError
        is FallbackLlmEngine -> e.lastError
        else -> null
    }

    private fun describe(t: Throwable?): String =
        t?.let { (it.message ?: it.javaClass.simpleName) } ?: "unknown error (see Settings > Diagnostics)"

    override suspend fun ensureSttLoaded() {
        idleJob?.cancel()
        loadMutex.withLock {
            val path = models.activeSttPath().first()
            if (path == null) {
                _sttState.value = EngineState(EngineStatus.NotConfigured, "No speech model downloaded")
                throw IllegalStateException("No speech model downloaded. Open the Models tab.")
            }
            val st = settings.settings.first()
            val threads = st.threads.coerceAtLeast(1)
            (stt as? FallbackSttEngine)?.threads = threads
            (stt as? FallbackSttEngine)?.flashMode = st.whisperFlashAttn
            if (loadedStt == path && loadedSttFlash == st.whisperFlashAttn && isReal(stt)) return
            if (loadedStt != null) unloadStt("flash-attention setting changed")
            if (lowRam && loadedLlm != null) unloadLlm("low RAM: making room for speech model")
            val name = File(path).name
            _sttState.value = EngineState(EngineStatus.Loading, "Loading $name", name)
            DebugLog.i("Engines", "loading speech model $name (threads=$threads)")
            val t0 = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) { stt.load(path) }
            } catch (e: CancellationException) {
                _sttState.value = EngineState(EngineStatus.Idle, modelFile = name); throw e
            } catch (e: Throwable) {
                fail(_sttState, "speech", name, e)
            }
            if (!isReal(stt)) fail(_sttState, "speech", name, lastError(stt))
            (stt as? FallbackSttEngine)?.threads = threads
            loadedStt = path
            loadedSttFlash = st.whisperFlashAttn
            val ms = System.currentTimeMillis() - t0
            _sttState.value = EngineState(EngineStatus.Ready, null, name, ms)
            DebugLog.i("Engines", "speech model ready in $ms ms")
        }
    }

    override suspend fun ensureLlmLoaded() {
        loadMutex.withLock {
            val path = models.activeLlmPath().first()
            if (path == null) {
                _llmState.value = EngineState(EngineStatus.NotConfigured, "No language model downloaded")
                throw IllegalStateException("No language model downloaded. Open the Models tab.")
            }
            val s = settings.settings.first()
            val key = LlmKey(path, s.threads.coerceAtLeast(1), s.contextSize)
            llmName = File(path).name
            if (loadedLlm == key && isReal(llm)) return
            if (loadedLlm != null) unloadLlm("settings or model changed")  // never hold two copies
            if (lowRam && loadedStt != null) unloadStt("low RAM: making room for language model")
            val name = File(path).name
            _llmState.value = EngineState(EngineStatus.Loading, "Loading $name", name)
            DebugLog.i("Engines", "loading language model $name (threads=${key.threads}, ctx=${key.ctx})")
            val t0 = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) { llm.load(path, LlmConfig(threads = key.threads, contextSize = key.ctx)) }
            } catch (e: CancellationException) {
                _llmState.value = EngineState(EngineStatus.Idle, modelFile = name); throw e
            } catch (e: Throwable) {
                fail(_llmState, "language", name, e)
            }
            if (!isReal(llm)) fail(_llmState, "language", name, lastError(llm))
            loadedLlm = key
            val ms = System.currentTimeMillis() - t0
            _llmState.value = EngineState(EngineStatus.Ready, null, name, ms)
            DebugLog.i("Engines", "language model ready in $ms ms")
        }
    }

    private fun fail(state: MutableStateFlow<EngineState>, what: String, name: String, t: Throwable?): Nothing {
        val msg = "Could not load the $what model ($name): ${describe(t)}"
        DebugLog.e("Engines", msg, t)
        state.value = EngineState(EngineStatus.Error, msg, name)
        throw IllegalStateException(msg, t)
    }

    override fun warmUpStt() {
        scope.launch { try { ensureSttLoaded(); releaseSttWhenIdle() } catch (e: CancellationException) { throw e } catch (_: Throwable) {} }
    }

    override suspend fun reloadStt() {
        loadMutex.withLock { if (loadedStt != null) unloadStt("reload requested") }
        ensureSttLoaded()
    }

    /**
     * Background LLM load. Only when auto-cleanup is on: with it off the language model must not load (RAM/CPU that the
     * speech decoder needs) until a style/cleanup is actually requested ([ensureLlmLoaded] from the rewrite path).
     */
    override fun warmUpLlm() {
        if (lowRam) return  // would evict the speech model that is about to be used
        scope.launch {
            if (!settings.settings.first().autoCleanup) return@launch
            try { ensureLlmLoaded() } catch (e: CancellationException) { throw e } catch (_: Throwable) {} }
    }

    override fun releaseSttWhenIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleReleaseMs)
            loadMutex.withLock { if (loadedStt != null) unloadStt("idle ${idleReleaseMs / 60_000} min") }
        }
    }
}
