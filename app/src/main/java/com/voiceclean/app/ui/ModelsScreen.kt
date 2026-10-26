package com.voiceclean.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voiceclean.app.di.AppContainer

/** Placeholder: M3 (model catalog, download, delete, select) fills this in. */
@Composable
fun ModelsScreen(container: AppContainer) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Models", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Whisper and LLM model downloads will appear here.")
    }
}
