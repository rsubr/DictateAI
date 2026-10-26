package com.voiceclean.app.ui

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceclean.app.di.AppContainer
import com.voiceclean.app.models.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(container: AppContainer) {
    val vm: ModelsViewModel = viewModel()
    val ctx = LocalContext.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    val used by vm.storageUsed.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.customBusy.collectAsStateWithLifecycle()
    val customError by vm.customError.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<CatalogModel?>(null) }
    var showCustom by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun startDownload(m: CatalogModel) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.download(m)
    }

    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Models", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Storage used: ${Formatter.formatFileSize(ctx, used)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { TokenCard(token) { vm.saveToken(it) } }
            ModelKind.entries.forEach { kind ->
                item {
                    Text(kind.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                }
                items(entries.filter { it.model.kind == kind }, key = { it.model.id }) { e ->
                    ModelCard(
                        e,
                        onDownload = { startDownload(e.model) },
                        onPause = { vm.pause(e.model) },
                        onCancel = { vm.cancel(e.model) },
                        onSelect = { vm.select(e.model) },
                        onDelete = { confirmDelete = e.model },
                    )
                }
            }
            item {
                OutlinedButton(onClick = { vm.clearCustomError(); showCustom = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add custom Hugging Face model")
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete model?") },
            text = { Text("${m.displayName} (${Formatter.formatFileSize(ctx, m.sizeBytes)}) will be removed from this device. You can download it again later.") },
            confirmButton = { TextButton(onClick = { vm.delete(m); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (showCustom) {
        CustomModelDialog(busy, customError, onDismiss = { showCustom = false }) { input, file, kind ->
            vm.addCustom(input, file, kind) { showCustom = false }
        }
    }
}

@Composable
private fun TokenCard(saved: String, onSave: (String) -> Unit) {
    var text by remember(saved) { mutableStateOf(saved) }
    var visible by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Hugging Face token (optional)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Only needed for gated repos or to avoid rate limits. All curated models are public.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("hf_...") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide token")
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(text) }, enabled = text.trim() != saved) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    e: ModelEntry,
    onDownload: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val m = e.model
    val dl = e.download
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.installed) {
                    RadioButton(selected = e.active, onClick = onSelect)
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(m.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Formatter.formatFileSize(ctx, m.sizeBytes)}  -  ${m.quant}  -  ~${m.ramHintMb} MB RAM",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (e.installed) {
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete ${m.displayName}") }
                }
            }
            if (m.tags.isNotEmpty() || m.gated || m.custom || e.active) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (e.active) AssistChip(onClick = {}, label = { Text("Active") })
                    m.tags.forEach { SuggestionChip(onClick = {}, label = { Text(it.label) }) }
                    if (m.custom) SuggestionChip(onClick = {}, label = { Text("Custom") })
                    if (m.gated) SuggestionChip(onClick = {}, label = { Text("Gated: token required") })
                }
            }
            Text(m.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))

            if (dl != null) {
                Spacer(Modifier.height(8.dp))
                if (dl.status == DownloadStatus.QUEUED || dl.status == DownloadStatus.VERIFYING || dl.total <= 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { dl.fraction }, Modifier.fillMaxWidth())
                }
                Text(progressLine(ctx, dl), style = MaterialTheme.typography.bodySmall,
                    color = if (dl.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            } else if (e.partialBytes > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { (e.partialBytes.toFloat() / m.sizeBytes).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text("Partially downloaded: ${Formatter.formatFileSize(ctx, e.partialBytes)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    dl != null && dl.isActive -> {
                        if (dl.status != DownloadStatus.VERIFYING) {
                            OutlinedButton(onClick = onPause) { Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(4.dp)); Text("Pause") }
                        }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    dl != null -> { // paused or failed
                        Button(onClick = onDownload) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp))
                            Text(if (dl.status == DownloadStatus.FAILED) "Retry" else "Resume")
                        }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    e.installed -> {
                        if (!e.active) Button(onClick = onSelect) { Text("Use this model") }
                    }
                    e.partialBytes > 0 -> {
                        Button(onClick = onDownload) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Resume") }
                        TextButton(onClick = onCancel) { Text("Discard") }
                    }
                    else -> Button(onClick = onDownload) {
                        Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("Download")
                    }
                }
            }
        }
    }
}

private fun progressLine(ctx: android.content.Context, d: DownloadProgress): String {
    val sz = "${Formatter.formatFileSize(ctx, d.downloaded)} / ${Formatter.formatFileSize(ctx, d.total)}"
    return when (d.status) {
        DownloadStatus.QUEUED -> "Starting..."
        DownloadStatus.VERIFYING -> "Verifying and finalizing..."
        DownloadStatus.PAUSED -> "Paused  -  $sz"
        DownloadStatus.FAILED -> d.message ?: "Failed"
        DownloadStatus.DOWNLOADING -> {
            d.message?.let { return "$it  -  $sz" }
            val speed = if (d.speedBps > 0) "${Formatter.formatFileSize(ctx, d.speedBps)}/s" else "..."
            val eta = if (d.etaSec >= 0) "  -  ${formatEta(d.etaSec)} left" else ""
            "$sz  -  $speed$eta"
        }
    }
}

internal fun formatEta(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    sec >= 60 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec}s"
}

@Composable
private fun CustomModelDialog(
    busy: Boolean, error: String?, onDismiss: () -> Unit, onAdd: (String, String, ModelKind) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ModelKind.LLM) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Custom Hugging Face model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(if (k == ModelKind.LLM) "LLM (.gguf)" else "STT (.bin)") })
                    }
                }
                OutlinedTextField(
                    input, { input = it }, label = { Text("File URL or owner/repo") }, singleLine = true,
                    placeholder = { Text("https://huggingface.co/.../resolve/main/x.gguf") },
                )
                OutlinedTextField(
                    file, { file = it }, label = { Text("File name (if repo given)") }, singleLine = true,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(input, file, kind) }, enabled = !busy && input.isNotBlank()) { Text("Validate and add") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}
