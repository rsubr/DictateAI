package com.voiceclean.app.ui

import androidx.compose.foundation.layout.*
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    }
}
