package com.voiceclean.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voiceclean.app.di.AppContainer
import com.voiceclean.app.llm.GenParams
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.launch

private val STYLES = listOf("Original", "Clean", "Formal", "Casual", "Shorter", "Longer")

/** Placeholder Home: fake STT/LLM engines. Real audio/streaming (M4) and cleanup (M5) replace the fakes. */
@Composable
fun HomeScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var recording by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("Clean") }
    var busy by remember { mutableStateOf(false) }

    fun stopAndProcess() {
        recording = false
        scope.launch {
            val text = container.sttEngine.transcribe(FloatArray(0), null)
            raw = text
            shown = text
            style = "Original"
        }
    }

    fun applyStyle(s: String) {
        style = s
        if (raw.isBlank()) return
        scope.launch {
            busy = true
            shown = if (s == "Original") raw else {
                // Always from raw, not stacked (PLAN.md). Fake engine just echoes with a tweak.
                val out = container.llmEngine.generate("$s\n$raw", GenParams())
                    .fold("") { a, b -> a + b }.trim()
                val cap = out.replaceFirstChar { it.uppercase() }
                if (cap.endsWith(".")) cap else "$cap."
            }
            busy = false
            container.historyRepository.add(raw, shown, s)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recording = true
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("VoiceClean", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        recording -> "Listening… (fake engine)"
                        shown.isNotEmpty() -> shown
                        else -> "Tap the mic to start dictating."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            STYLES.forEach { s ->
                FilterChip(selected = style == s, onClick = { applyStyle(s) }, label = { Text(s) }, enabled = !busy && !recording)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.size(48.dp))
            LargeFloatingActionButton(onClick = {
                if (recording) stopAndProcess()
                else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = true
                else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }) { Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, "Mic", Modifier.size(40.dp)) }
            FilledTonalIconButton(onClick = { clipboard.setText(AnnotatedString(shown)) }, enabled = shown.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.ContentCopy, "Copy")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
