package com.voiceclean.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceclean.app.data.TranscriptionEntity
import com.voiceclean.app.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return " · %d:%02d".format(s / 60, s % 60)
}

/** @param onOpen reopen the entry in Home. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun HistoryScreen(container: AppContainer, onOpen: (Long) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val query = remember { MutableStateFlow("") }
    val q by query.collectAsStateWithLifecycle()
    val items by remember { query.flatMapLatest { container.historyRepository.observe(it) } }
        .collectAsStateWithLifecycle(emptyList<TranscriptionEntity>())
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var confirmAll by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmAll = true }, enabled = items.isNotEmpty()) {
                    Icon(Icons.Filled.DeleteSweep, null); Spacer(Modifier.width(4.dp)); Text("Delete all")
                }
            }
            OutlinedTextField(
                q, { query.value = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ query.value = "" }) { Icon(Icons.Filled.Clear, "Clear search") } },
            )
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                Text(if (q.isBlank()) "No transcriptions yet." else "No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { e ->
                    val open = e.id in expanded
                    Card(Modifier.fillMaxWidth().clickable { onOpen(e.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${e.style} · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.createdAt)) + fmtDuration(e.durationMs),
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                                )
                                IconButton({ expanded = if (open) expanded - e.id else expanded + e.id }) {
                                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse" else "Expand")
                                }
                            }
                            Text(e.cleanedText, maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                            if (open) {
                                if (e.rawText != e.cleanedText) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(e.rawText, style = MaterialTheme.typography.bodySmall)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton({
                                        clipboard.setText(AnnotatedString(e.cleanedText))
                                        scope.launch { snackbar.showSnackbar("Copied to clipboard") }
                                    }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                                    IconButton({ scope.launch { container.historyRepository.delete(e) } }) { Icon(Icons.Filled.Delete, "Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all history?") },
            text = { Text("This permanently removes every saved transcription.") },
            confirmButton = { TextButton({ confirmAll = false; scope.launch { container.historyRepository.clear() } }) { Text("Delete all") } },
            dismissButton = { TextButton({ confirmAll = false }) { Text("Cancel") } },
        )
    }
}
