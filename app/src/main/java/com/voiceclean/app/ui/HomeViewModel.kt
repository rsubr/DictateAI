package com.voiceclean.app.ui

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voiceclean.app.audio.AudioRecorder
import com.voiceclean.app.audio.AudioSource
import com.voiceclean.app.audio.EnergyVad
import com.voiceclean.app.audio.MicPermissionException
import com.voiceclean.app.data.Settings
import com.voiceclean.app.di.AppContainer
import com.voiceclean.app.di.EngineStatus
import com.voiceclean.app.llm.CleanupService
import com.voiceclean.app.llm.Style
import com.voiceclean.app.stt.StreamingTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Single-level undo for one rewrite: [original] is the whole box before it; the rewritten range is [start, resultEnd). */
data class RewriteUndo(val original: TextFieldValue, val start: Int, val resultEnd: Int)

data class HomeUiState(
    val recording: Boolean = false,
    /** Mic is capturing but the speech model is still loading (audio is buffered). */
    val loadingModel: Boolean = false,
    val level: Float = 0f,
    /** The one editable text box: text + selection. */
    val box: TextFieldValue = TextFieldValue(""),
    /** Provisional (not yet committed) dictated range inside [box], rendered dimmed; null when none. */
    val provisional: IntRange? = null,
    val style: Style = Style.Original,
    val undo: RewriteUndo? = null,
    val cleaning: Boolean = false,
    val sttAvailable: Boolean = true,
    val llmAvailable: Boolean = true,
    /** Human-readable engine load progress/error ("Loading ...", "Could not load ..."), null when fine. */
    val engineMsg: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** True while a Clear can be undone (screen shows an Undo snackbar). */
    val clearUndoable: Boolean = false,
) {
    val hasText: Boolean get() = box.text.isNotBlank()
    val hasResult: Boolean get() = hasText
}

class HomeViewModel(
    private val container: AppContainer,
    appContext: Context,
    private val audioSource: AudioSource = AudioRecorder(appContext),
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val cleanup = CleanupService(
        engine = { container.llmEngine },
        modelName = { container.engineProvider.llmModelName() },
    )

    private var transcriber: StreamingTranscriber? = null
    private var startJob: Job? = null
    private var captureJob: Job? = null
    private var audioChannel: Channel<FloatArray>? = null
    private var observeJob: Job? = null
    private var cleanupJob: Job? = null
    private var persistJob: Job? = null
    private var historyId: Long = 0
    private var startedAt = 0L
    private var durationMs = 0L

    // Dictation anchor (valid while recording / until finalized)
    private var baseValue = TextFieldValue("")
    private var dictAnchor = 0
    private var dictSelEnd = 0
    private var dictInserted = 0
    private var lastDictation = ""

    private var clearedSnapshot: Triple<TextFieldValue, Long, Style>? = null

    init {
        container.engineProvider.warmUpStt()
        viewModelScope.launch {
            combine(container.engineProvider.sttState, container.engineProvider.llmState) { a, b -> a to b }
                .collect { (a, b) ->
                    val msg = when {
                        a.status == EngineStatus.Loading -> "Loading speech model..."
                        b.status == EngineStatus.Loading -> "Loading language model..."
                        a.status == EngineStatus.Error -> a.message
                        b.status == EngineStatus.Error -> b.message
                        else -> null
                    }
                    _state.update { it.copy(engineMsg = msg) }
                }
        }
        viewModelScope.launch {
            combine(container.engineProvider.sttAvailable, container.engineProvider.llmAvailable) { a, b -> a to b }
                .collect { (s, l) -> _state.update { it.copy(sttAvailable = s, llmAvailable = l) } }
        }
    }

    fun dismissMessage() = _state.update { it.copy(error = null, notice = null) }

    fun onMicPermissionDenied() = _state.update { it.copy(error = "Microphone permission is required to dictate.") }

    fun toggleRecording() {
        if (_state.value.recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!_state.value.sttAvailable) {
            _state.update { it.copy(error = "Download a speech model first.") }
            return
        }
        if (_state.value.cleaning) cancelCleanup()
        persistJob?.cancel()
        clearedSnapshot = null
        val f = _state.value.box
        baseValue = f
        dictAnchor = f.selection.min
        dictSelEnd = f.selection.max
        dictInserted = 0
        lastDictation = ""
        startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(recording = true, loadingModel = true, provisional = null, undo = null, style = Style.Original,
                error = null, notice = null, clearUndoable = false)
        }
        // Capture immediately; audio is buffered until the speech model is loaded and the transcriber started.
        val ch = Channel<FloatArray>(Channel.UNLIMITED)
        audioChannel = ch
        captureJob = viewModelScope.launch {
            try {
                audioSource.chunks().collect { ch.trySend(it) }
                ch.close()
            } catch (e: CancellationException) {
                ch.close(); throw e
            } catch (e: Throwable) {
                ch.close(e)
            }
        }
        startJob = viewModelScope.launch {
            try {
                container.engineProvider.ensureSttLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                captureJob?.cancel()
                finishSession(null)
                _state.update { it.copy(error = e.message ?: "Could not load the speech model") }
                return@launch
            }
            val t = StreamingTranscriber(container.sttEngine, EnergyVad())
            transcriber = t
            t.start(viewModelScope, ch.receiveAsFlow())
            _state.update { it.copy(loadingModel = false) }
            observeJob = launch {
                launch { t.transcript.collect { tr -> onTranscript(tr.full, tr.partial, tr.committed) } }
                launch { t.level.collect { l -> _state.update { it.copy(level = l) } } }
                launch {
                    t.error.collect { e ->
                        if (e != null) {
                            _state.update {
                                it.copy(error = if (e is MicPermissionException) "Microphone permission is required to dictate."
                                else "Audio/transcription error: ${e.message}")
                            }
                            if (_state.value.recording) stopRecording()
                        }
                    }
                }
                launch { t.autoStopped.collect { if (it && _state.value.recording) stopRecording(auto = true) } }
            }
            // Only touch the LLM during dictation if auto-cleanup is on.
            val s: Settings = container.settingsRepository.settings.first()
            if (s.autoCleanup && _state.value.llmAvailable) container.engineProvider.warmUpLlm()
        }
    }

    /** Re-renders the whole dictation (committed + provisional) into the box at the anchor. */
    private fun onTranscript(full: String, partial: String, committed: String) {
        if (!_state.value.recording) return
        renderDictation(full, partialLen = if (partial.isBlank()) 0 else partial.trim().length, final = false)
    }

    private fun renderDictation(dictation: String, partialLen: Int, final: Boolean) {
        lastDictation = dictation
        if (dictation.isBlank()) {
            dictInserted = 0
            _state.update { it.copy(box = baseValue, provisional = null) }
            return
        }
        val cur = _state.value.box.text
        val r = DictationInsert.apply(cur, dictAnchor, dictSelEnd, dictInserted, dictation)
        dictInserted = r.insertedLen
        val prov = if (!final && partialLen > 0) (r.dictStart + r.dictLen - partialLen) until (r.dictStart + r.dictLen) else null
        _state.update { it.copy(box = TextFieldValue(r.text, TextRange(r.caret)), provisional = prov) }
    }

    fun stopRecording(auto: Boolean = false) {
        if (!_state.value.recording) return
        val t = transcriber
        transcriber = null
        captureJob?.cancel()
        startJob?.cancel()
        _state.update { it.copy(recording = false, loadingModel = false, level = 0f, notice = if (auto) "Stopped after a long silence." else it.notice) }
        durationMs = System.currentTimeMillis() - startedAt
        viewModelScope.launch {
            val text = t?.stop()?.trim().orEmpty()
            container.engineProvider.releaseSttWhenIdle()
            observeJob?.cancel()
            finishSession(text)
        }
    }

    /** Finalizes the dictation: [text] is the full dictated text of this session (null = aborted). */
    private suspend fun finishSession(text: String?) {
        if (text.isNullOrBlank()) {
            _state.update { it.copy(recording = false, loadingModel = false, box = baseValue, provisional = null,
                notice = if (text != null) "No speech detected." else it.notice) }
            return
        }
        renderDictation(text, 0, final = true)
        val st = _state.value
        val dictStart = (st.box.selection.end - text.length).coerceAtLeast(0)
        val dictEnd = st.box.selection.end
        historyId = container.historyRepository.add(text, st.box.text, Style.Original.label, durationMs)
        val s: Settings = container.settingsRepository.settings.first()
        if (s.autoCleanup && st.llmAvailable) rewriteRange(Style.Clean, dictStart, dictEnd)
    }

    /** Applies [style] to the selected text if any, else to the whole box text. Base = text at the time of the tap. */
    fun applyStyle(style: Style) {
        val st = _state.value
        if (st.recording || st.cleaning) return
        if (style == Style.Original) { restoreOriginal(); return }
        val sel = st.box.selection
        if (!sel.collapsed) rewriteRange(style, sel.min, sel.max) else rewriteRange(style, 0, st.box.text.length)
    }

    private fun restoreOriginal() {
        val u = _state.value.undo ?: return
        cleanupJob?.cancel()
        _state.update { it.copy(box = u.original, undo = null, style = Style.Original, cleaning = false, provisional = null) }
        persist()
    }

    private fun rewriteRange(style: Style, rawStart: Int, rawEnd: Int) {
        val st = _state.value
        val orig = st.box
        val text = orig.text
        var start = rawStart.coerceIn(0, text.length)
        var end = rawEnd.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return
        if (!st.llmAvailable) {
            _state.update { it.copy(error = "Download a language model to rewrite text.") }
            return
        }
        val base = text.substring(start, end)
        rewriteOrig = orig
        cleanupJob?.cancel()
        persistJob?.cancel()
        _state.update { it.copy(style = style, cleaning = true, error = null, provisional = null) }
        fun compose(mid: String) = text.substring(0, start) + mid + text.substring(end)
        cleanupJob = viewModelScope.launch {
            try {
                container.engineProvider.ensureLlmLoaded()
                val r = cleanup.run(base, style) { partial ->
                    _state.update { it.copy(box = TextFieldValue(compose(partial), TextRange(start + partial.length))) }
                }
                val resultEnd = start + r.text.length
                _state.update {
                    it.copy(box = TextFieldValue(compose(r.text), TextRange(resultEnd)), cleaning = false,
                        undo = if (r.text != base) RewriteUndo(orig, start, resultEnd) else null,
                        style = if (r.text != base) style else Style.Original,
                        notice = if (r.fellBack) "Rewrite looked unreliable (${r.reason}); kept the original text." else it.notice)
                }
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(cleaning = false, box = orig, style = Style.Original, undo = null, error = e.message ?: "Rewrite failed") }
            }
        }
    }

    /** App went to the background: Android blocks background mic capture, so end the session cleanly. */
    fun onAppBackgrounded() {
        if (_state.value.recording) stopRecording()
    }

    fun cancelCleanup() {
        cleanupJob?.cancel()
        val o = rewriteOrig
        _state.update { it.copy(cleaning = false, style = Style.Original, box = o ?: it.box) }
    }

    /** Whole box as it was before the in-flight rewrite started. */
    private var rewriteOrig: TextFieldValue? = null

    /** User typed in / moved the caret in the text box (ignored while the box is read-only). */
    fun onFieldChanged(v: TextFieldValue) {
        val st = _state.value
        if (st.recording || st.cleaning) return
        val textChanged = v.text != st.box.text
        _state.update { it.copy(box = v, undo = if (textChanged) null else it.undo,
            style = if (textChanged) Style.Original else it.style, provisional = null) }
        if (textChanged) {
            persistJob?.cancel()
            persistJob = viewModelScope.launch { delay(800); persist() }
        }
    }

    /** Text to copy: the selection if any, else everything. */
    fun copyText(): String {
        val f = _state.value.box
        return if (f.selection.collapsed) f.text else f.text.substring(f.selection.min, f.selection.max)
    }

    private fun persist() {
        val st = _state.value
        val id = historyId
        if (id == 0L) return
        viewModelScope.launch {
            val e = container.historyRepository.get(id) ?: return@launch
            container.historyRepository.update(e.copy(cleanedText = st.box.text, style = st.style.label))
        }
    }

    /** Clear (can be undone via [undoClear] while the snackbar is up). */
    fun newSession() {
        val st = _state.value
        val snap = if (st.box.text.isNotEmpty()) Triple(st.box, historyId, st.style) else null
        if (st.recording) {
            val t = transcriber; transcriber = null
            captureJob?.cancel(); startJob?.cancel()
            viewModelScope.launch { t?.stop(); container.engineProvider.releaseSttWhenIdle() }
        }
        observeJob?.cancel(); cleanupJob?.cancel(); persistJob?.cancel()
        historyId = 0
        clearedSnapshot = snap
        _state.update {
            HomeUiState(sttAvailable = it.sttAvailable, llmAvailable = it.llmAvailable, engineMsg = it.engineMsg,
                clearUndoable = snap != null)
        }
    }

    fun undoClear() {
        val (f, id, style) = clearedSnapshot ?: return
        clearedSnapshot = null
        historyId = id
        _state.update { it.copy(box = f, style = style, clearUndoable = false) }
    }

    fun dropClearUndo() {
        clearedSnapshot = null
        _state.update { it.copy(clearUndoable = false) }
    }

    fun open(id: Long) {
        viewModelScope.launch {
            val e = container.historyRepository.get(id) ?: return@launch
            if (_state.value.recording) newSession()
            cleanupJob?.cancel()
            clearedSnapshot = null
            historyId = e.id
            _state.update {
                it.copy(box = TextFieldValue(e.cleanedText, TextRange(e.cleanedText.length)), undo = null,
                    style = Style.fromLabel(e.style), cleaning = false, provisional = null, clearUndoable = false,
                    error = null, notice = null)
            }
        }
    }

    override fun onCleared() {
        // engines/transcriber stop with viewModelScope cancellation
        super.onCleared()
    }

    class Factory(private val container: AppContainer, private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(container, ctx.applicationContext) as T
    }
}
