package com.voiceclean.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun HistoryScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val query = remember { MutableStateFlow("") }
    val q by query.collectAsStateWithLifecycle()
    val items by remember { query.flatMapLatest { container.historyRepository.observe(it) } }
        .collectAsStateWithLifecycle(emptyList<TranscriptionEntity>())
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(q, { query.value = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) Text("No transcriptions yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${e.style} · " + DateFormat.getDateTimeInstance().format(Date(e.createdAt)), style = MaterialTheme.typography.labelSmall)
                            Text(e.cleanedText)
                            Text(e.rawText, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { scope.launch { container.historyRepository.delete(e) } }) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    }
                }
            }
        }
    }
}
