package com.voiceclean.app.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.voiceclean.app.NativeBridge
import com.voiceclean.app.di.AppContainer
import com.voiceclean.app.di.EngineState
import com.voiceclean.app.llm.GenParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device info, engine states, debug-log report and the in-app self-test. Lives in the app scope (survives rotation). */
class Diagnostics(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _selfTest = MutableStateFlow("")
    val selfTestReport: StateFlow<String> = _selfTest.asStateFlow()
    private val _running = MutableStateFlow(false)
    val selfTestRunning: StateFlow<Boolean> = _running.asStateFlow()
    private var job: Job? = null

    fun deviceInfo(): String {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val mb = 1024 * 1024
        return buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("SoC: ${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("RAM: total ${mi.totalMem / mb} MB, available ${mi.availMem / mb} MB, low=${mi.lowMemory}")
            appendLine("CPU: ${CpuInfo.describe()}; default threads=${CpuInfo.defaultThreads()}")
            appendLine("Native lib loaded: ${NativeBridge.isLoaded}; ${NativeBridge.version()}")
            appendLine("ggml CPU features: ${NativeBridge.systemInfo().ifBlank { "(backends not initialised yet: load a model first)" }}")
        }
    }

    private fun fmt(name: String, s: EngineState) =
        "$name: ${s.status}${s.modelFile?.let { " [$it]" } ?: ""}${if (s.loadMs > 0) " load=${s.loadMs}ms" else ""}${s.message?.let { " - $it" } ?: ""}"

    fun engineInfo(): String = buildString {
        val p = container.engineProvider
        appendLine(fmt("STT", p.sttState.value))
        appendLine(fmt("LLM", p.llmState.value))
    }

    /** Everything the user should paste back when reporting a problem. */
    fun fullReport(): String = buildString {
        appendLine("=== VoiceClean debug report ===")
        append(deviceInfo()); append(engineInfo())
        if (_selfTest.value.isNotBlank()) { appendLine("--- self-test ---"); appendLine(_selfTest.value) }
        appendLine("--- app log (recent) ---")
        appendLine(DebugLog.recent().takeLast(12000))
        appendLine("--- native log (ggml/llama/whisper) ---")
        appendLine(NativeBridge.nativeLogText().takeLast(12000))
    }

    fun runSelfTest() {
        if (_running.value) return
        _running.value = true
        _selfTest.value = ""
        job = scope.launch(Dispatchers.Default) {
            val sb = StringBuilder()
            fun line(s: String) { sb.appendLine(s); _selfTest.value = sb.toString(); DebugLog.i("SelfTest", s) }
            try {
                line("Self-test started")
                val p = container.engineProvider
                // --- STT ---
                try {
                    val t0 = System.currentTimeMillis()
                    p.ensureSttLoaded()
                    line("STT load: ${System.currentTimeMillis() - t0} ms (${p.sttState.value.modelFile})")
                    val pcm = FloatArray(16000)
                    val t1 = System.currentTimeMillis()
                    val text = container.sttEngine.transcribe(pcm, null)
                    val ms = System.currentTimeMillis() - t1
                    line("STT transcribe 1 s silence: $ms ms (realtime factor ${"%.2f".format(ms / 1000.0)}), text='${text}'")
                    line("STT: OK")
                } catch (e: Throwable) {
                    line("STT FAILED: ${e.message ?: e.javaClass.simpleName}")
                    DebugLog.e("SelfTest", "stt", e)
                }
                // --- LLM ---
                try {
                    val t0 = System.currentTimeMillis()
                    p.ensureLlmLoaded()
                    line("LLM load: ${System.currentTimeMillis() - t0} ms (${p.llmState.value.modelFile})")
                    val t1 = System.currentTimeMillis()
                    var first = -1L
                    var pieces = 0
                    val out = StringBuilder()
                    val name = p.llmModelName()
                    val user = if (name.contains("qwen3", true) && !name.contains("instruct", true)) "Say OK /no_think" else "Say OK"
                    container.llmEngine.generate(
                        user,
                        GenParams(temperature = 0f, maxTokens = 24, systemPrompt = "You are a helpful assistant. Answer very briefly."),
                    ).collect { piece ->
                        if (first < 0) first = System.currentTimeMillis() - t1
                        pieces++; out.append(piece)
                    }
                    val total = System.currentTimeMillis() - t1
                    val decodeMs = (total - first.coerceAtLeast(0)).coerceAtLeast(1)
                    val tps = if (pieces > 1) (pieces - 1) * 1000.0 / decodeMs else 0.0
                    line("LLM generate: first token ${first} ms, total $total ms, $pieces tokens, ~${"%.1f".format(tps)} tok/s")
                    line("LLM output: '${out.toString().trim().take(200)}'")
                    line(if (pieces > 0) "LLM: OK" else "LLM: produced no tokens")
                } catch (e: Throwable) {
                    line("LLM FAILED: ${e.message ?: e.javaClass.simpleName}")
                    DebugLog.e("SelfTest", "llm", e)
                }
                line("Self-test finished")
            } finally {
                _running.value = false
            }
        }
    }
}
