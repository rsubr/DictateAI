package com.voiceclean.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.voiceclean.app.di.EngineState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceclean.app.NativeBridge
import com.voiceclean.app.data.Settings
import com.voiceclean.app.di.AppContainer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer) {
    val repo = container.settingsRepository
    val scope = rememberCoroutineScope()
    val s by repo.settings.collectAsStateWithLifecycle(Settings())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("LLM backend")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.llmBackend == "llama.cpp", { scope.launch { repo.setBackend("llama.cpp") } }, { Text("llama.cpp") })
            FilterChip(false, {}, { Text("ExecuTorch-QNN (coming soon)") }, enabled = false)
        }
        Text("Threads: ${s.threads}")
        Slider(s.threads.toFloat(), { scope.launch { repo.setThreads(it.toInt()) } }, valueRange = 1f..8f, steps = 6)
        Text("Context size: ${s.contextSize}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2048, 4096, 8192).forEach { c ->
                FilterChip(s.contextSize == c, { scope.launch { repo.setContextSize(c) } }, { Text("$c") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-cleanup after dictation", Modifier.weight(1f))
            Switch(s.autoCleanup, { scope.launch { repo.setAutoCleanup(it) } })
        }
        Text("Native: ${NativeBridge.version()}", style = MaterialTheme.typography.bodySmall)
        Text("Threads and context size apply the next time the language model loads (reloaded automatically).",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        DiagnosticsSection(container)
    }
}

@Composable
private fun DiagnosticsSection(container: AppContainer) {
    val diag = container.diagnostics
    val provider = container.engineProvider
    val clipboard = LocalClipboardManager.current
    val stt by provider.sttState.collectAsStateWithLifecycle()
    val llm by provider.llmState.collectAsStateWithLifecycle()
    val report by diag.selfTestReport.collectAsStateWithLifecycle()
    val running by diag.selfTestRunning.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    // Refresh device/engine info when engine states change (also reads native CPU variant info).
    LaunchedEffect(stt, llm, running) { info = diag.deviceInfo() + diag.engineInfo() }

    Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
    SelectionContainer { Text(info, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { diag.runSelfTest() }, enabled = !running) { Text(if (running) "Running..." else "Self-test") }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diag.fullReport())); copied = true }) {
            Text(if (copied) "Copied" else "Copy debug log")
        }
    }
    val flash = container.settingsRepository.settings.collectAsStateWithLifecycle(Settings()).value.whisperFlashAttn
    val scope = rememberCoroutineScope()
    Text("Whisper flash attention (applies at next speech-model load)", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto", "on", "off").forEach { m ->
            FilterChip(flash == m, { scope.launch { container.settingsRepository.setWhisperFlashAttn(m) } }, { Text(m) })
        }
    }
    Text("Self-test decodes a 5 s speech-like signal twice (partial) plus once (final), benchmarks flash attention on/off, generates a short reply with the LLM, and reports timings.",
        style = MaterialTheme.typography.bodySmall)
    if (report.isNotBlank()) SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
}
