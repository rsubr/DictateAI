package com.voiceclean.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceclean.app.di.AppContainer
import com.voiceclean.app.llm.Style
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val CHIP_STYLES = listOf(Style.Clean, Style.Formal, Style.Casual, Style.Shorter, Style.Longer, Style.Original)

/**
 * @param openRequest history id to reopen (set by History), consumed here.
 * @param onGoToModels navigate to the Models tab.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    openRequest: MutableStateFlow<Long?> = MutableStateFlow(null),
    onGoToModels: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container, ctx))
    val s by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val req by openRequest.collectAsStateWithLifecycle()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.onAppBackgrounded()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(req) {
        req?.let { vm.open(it); openRequest.value = null }
    }
    LaunchedEffect(s.error) { s.error?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(s.notice) { s.notice?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(s.clearUndoable) {
        if (s.clearUndoable) {
            val r = snackbar.showSnackbar("Text cleared", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.undoClear() else vm.dropClearUndo()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleRecording() else vm.onMicPermissionDenied()
    }
    fun onMic() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (s.recording || granted) vm.toggleRecording() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("VoiceClean", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (s.hasText || s.recording) {
                    TextButton(onClick = vm::newSession) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("New")
                    }
                }
            }
            if (!s.sttAvailable || !s.llmAvailable) {
                ModelsBanner(needStt = !s.sttAvailable, needLlm = !s.llmAvailable, onGoToModels)
                Spacer(Modifier.height(8.dp))
            }

            s.engineMsg?.let { m ->
                Text(m, style = MaterialTheme.typography.bodySmall,
                    color = if (m.startsWith("Loading")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    TextEditor(s, vm)
                    if (!s.hasText) {
                        Text(
                            when {
                                s.recording && s.loadingModel -> "Loading speech model..."
                                s.recording -> "Listening..."
                                s.sttAvailable -> "Tap the mic and start talking, or type here.\nDictation is inserted at the cursor."
                                else -> "Download a speech model to start dictating."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else if (s.recording && s.loadingModel) {
                        Text("Loading speech model...", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomStart))
                    }
                }
            }
            if (s.cleaning) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(Modifier.weight(1f))
                    TextButton(onClick = vm::cancelCleanup) { Text("Cancel") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CHIP_STYLES.forEach { st ->
                    FilterChip(
                        selected = s.style == st && s.hasText && (st != Style.Original || s.undo != null),
                        onClick = { vm.applyStyle(st) },
                        label = { Text(st.label) },
                        enabled = s.hasText && !s.recording && !s.cleaning && (if (st == Style.Original) s.undo != null else s.llmAvailable),
                        leadingIcon = if (s.cleaning && s.style == st) {
                            { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                        } else null,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalIconButton(
                    onClick = { clipboard.setText(AnnotatedString(vm.copyText())); scope.launch { snackbar.showSnackbar("Copied to clipboard") } },
                    enabled = s.hasText && !s.recording, modifier = Modifier.size(52.dp),
                ) { Icon(Icons.Filled.ContentCopy, "Copy") }
                MicButton(recording = s.recording, busy = s.recording && s.loadingModel, level = s.level, enabled = s.sttAvailable, onClick = ::onMic)
                FilledTonalIconButton(
                    onClick = vm::newSession, enabled = s.hasText || s.recording, modifier = Modifier.size(52.dp),
                ) { Icon(Icons.Filled.Clear, "Clear") }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }
}

@Composable
private fun ModelsBanner(needStt: Boolean, needLlm: Boolean, onGoToModels: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    needStt && needLlm -> "Download a speech model and a language model to get started."
                    needStt -> "Download a speech (Whisper) model to dictate."
                    else -> "Download a language model to clean up and rewrite text."
                },
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onGoToModels) { Text("Download a model") }
        }
    }
}

/** Dims the provisional (not yet committed) dictated range. */
private class ProvisionalTransformation(private val range: IntRange?, private val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val r = range
        val out = if (r == null || r.first < 0 || r.last >= text.length || r.isEmpty()) text
        else buildAnnotatedString {
            append(text)
            addStyle(style, r.first, r.last + 1)
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

@Composable
private fun TextEditor(s: HomeUiState, vm: HomeViewModel) {
    val color = MaterialTheme.colorScheme.onSurface
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableIntStateOf(0) }
    val dim = SpanStyle(color = color.copy(alpha = 0.45f), fontStyle = FontStyle.Italic)
    val transformation = remember(s.provisional, color) { ProvisionalTransformation(s.provisional, dim) }
    // Keep the caret / inserted text in view while dictation or a rewrite streams in.
    LaunchedEffect(s.box.text, s.box.selection, layout, viewport, s.recording, s.cleaning) {
        if (!s.recording && !s.cleaning) return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        val caret = s.box.selection.end.coerceIn(0, s.box.text.length)
        val bottom = l.getCursorRect(caret).bottom.toInt()
        scroll.animateScrollTo((bottom - viewport + 24).coerceIn(0, scroll.maxValue))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it.height }.verticalScroll(scroll)) {
        BasicTextField(
            value = s.box,
            onValueChange = vm::onFieldChanged,
            readOnly = s.recording || s.cleaning,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = color),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 200.dp),
        )
    }
}

@Composable
private fun MicButton(recording: Boolean, busy: Boolean, level: Float, enabled: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val p by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    val ring by animateFloatAsState(if (recording) 1f + level.coerceIn(0f, 1f) * 0.55f else 1f, label = "ring")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier.size(88.dp).scale(ring * p).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.22f)),
            )
        }
        LargeFloatingActionButton(
            onClick = onClick,
            containerColor = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
            else Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, if (recording) "Stop" else "Start dictation", Modifier.size(40.dp))
        }
    }
}
