package com.voiceclean.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voiceclean.app.audio.AudioRecorder
import com.voiceclean.app.audio.AudioSource
import com.voiceclean.app.audio.EnergyVad
import com.voiceclean.app.audio.MicPermissionException
import com.voiceclean.app.data.Settings
import com.voiceclean.app.di.AppContainer
import com.voiceclean.app.llm.CleanupService
import com.voiceclean.app.llm.Style
import com.voiceclean.app.stt.StreamingTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val recording: Boolean = false,
    val starting: Boolean = false,
    val committed: String = "",
    val partial: String = "",
    val level: Float = 0f,
    /** Base text of this session; styles always rewrite this. */
    val raw: String = "",
    /** Editable result shown after dictation. */
    val result: String = "",
    val style: Style = Style.Original,
    val cleaning: Boolean = false,
    val sttAvailable: Boolean = true,
    val llmAvailable: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
) {
    val hasResult: Boolean get() = raw.isNotBlank()
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
    private var observeJob: Job? = null
    private var cleanupJob: Job? = null
    private var persistJob: Job? = null
    private var historyId: Long = 0
    private var startedAt = 0L
    private var durationMs = 0L

    init {
        viewModelScope.launch {
            combine(container.engineProvider.sttAvailable, container.engineProvider.llmAvailable) { a, b -> a to b }
                .collect { (s, l) -> _state.update { it.copy(sttAvailable = s, llmAvailable = l) } }
        }
    }

    fun dismissMessage() = _state.update { it.copy(error = null, notice = null) }

    fun onMicPermissionDenied() = _state.update { it.copy(error = "Microphone permission is required to dictate.") }

    fun toggleRecording() {
        if (_state.value.recording || _state.value.starting) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (!_state.value.sttAvailable) {
            _state.update { it.copy(error = "Download a speech model first.") }
            return
        }
        cleanupJob?.cancel()
        persistJob?.cancel()
        historyId = 0
        _state.update {
            it.copy(starting = true, recording = false, committed = "", partial = "", raw = "", result = "",
                style = Style.Original, cleaning = false, error = null, notice = null)
        }
        viewModelScope.launch {
            try {
                container.engineProvider.ensureSttLoaded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(starting = false, error = "Could not load the speech model: ${e.message}") }
                return@launch
            }
            if (!_state.value.starting) return@launch // user cancelled while loading
            val t = StreamingTranscriber(container.sttEngine, EnergyVad())
            transcriber = t
            startedAt = System.currentTimeMillis()
            t.start(viewModelScope, audioSource.chunks())
            _state.update { it.copy(starting = false, recording = true) }
            observeJob = launch {
                launch { t.transcript.collect { tr -> _state.update { it.copy(committed = tr.committed, partial = tr.partial) } } }
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
        }
    }

    fun stopRecording(auto: Boolean = false) {
        val t = transcriber
        if (t == null) { _state.update { it.copy(starting = false, recording = false) }; return }
        if (!_state.value.recording) { _state.update { it.copy(starting = false) }; return }
        transcriber = null
        _state.update { it.copy(recording = false, level = 0f, notice = if (auto) "Stopped after a long silence." else it.notice) }
        durationMs = System.currentTimeMillis() - startedAt
        viewModelScope.launch {
            val text = t.stop().trim()
            observeJob?.cancel()
            _state.update { it.copy(committed = "", partial = "") }
            if (text.isBlank()) {
                _state.update { it.copy(notice = "No speech detected.") }
                return@launch
            }
            _state.update { it.copy(raw = text, result = text, style = Style.Original) }
            historyId = container.historyRepository.add(text, text, Style.Original.label, durationMs)
            val s: Settings = container.settingsRepository.settings.first()
            if (s.autoCleanup && _state.value.llmAvailable) applyStyle(Style.Clean)
        }
    }

    /** Applies [style] to the RAW text (never to a previous rewrite). */
    fun applyStyle(style: Style) {
        val st = _state.value
        if (st.raw.isBlank() || st.recording) return
        cleanupJob?.cancel()
        persistJob?.cancel()
        if (style == Style.Original) {
            _state.update { it.copy(style = style, result = it.raw, cleaning = false) }
            persist()
            return
        }
        if (!st.llmAvailable) {
            _state.update { it.copy(error = "Download a language model to rewrite text.") }
            return
        }
        val prevResult = st.result
        val prevStyle = st.style
        _state.update { it.copy(style = style, cleaning = true, error = null) }
        cleanupJob = viewModelScope.launch {
            try {
                container.engineProvider.ensureLlmLoaded()
                val r = cleanup.run(st.raw, style) { partial -> _state.update { it.copy(result = partial) } }
                _state.update {
                    it.copy(result = r.text, cleaning = false,
                        notice = if (r.fellBack) "Rewrite looked unreliable (${r.reason}); showing original text." else it.notice)
                }
                persist()
            } catch (e: CancellationException) {
                _state.update { if (it.cleaning && it.raw == st.raw && cleanupJob?.isActive != true) it.copy(cleaning = false) else it }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(cleaning = false, result = prevResult, style = prevStyle, error = "Rewrite failed: ${e.message}") }
            }
        }
    }

    fun cancelCleanup() {
        val st = _state.value
        cleanupJob?.cancel()
        _state.update { it.copy(cleaning = false, result = st.raw, style = Style.Original) }
    }

    /** User typed in the result box. */
    fun onResultEdited(text: String) {
        _state.update { it.copy(result = text) }
        persistJob?.cancel()
        persistJob = viewModelScope.launch { delay(800); persist() }
    }

    private fun persist() {
        val st = _state.value
        val id = historyId
        if (id == 0L) return
        viewModelScope.launch {
            val e = container.historyRepository.get(id) ?: return@launch
            container.historyRepository.update(e.copy(rawText = st.raw, cleanedText = st.result, style = st.style.label))
        }
    }

    fun newSession() {
        if (_state.value.recording) {
            val t = transcriber; transcriber = null
            viewModelScope.launch { t?.stop() }
        }
        observeJob?.cancel(); cleanupJob?.cancel(); persistJob?.cancel()
        historyId = 0
        _state.update { HomeUiState(sttAvailable = it.sttAvailable, llmAvailable = it.llmAvailable) }
    }

    fun open(id: Long) {
        viewModelScope.launch {
            val e = container.historyRepository.get(id) ?: return@launch
            if (_state.value.recording) newSession()
            cleanupJob?.cancel()
            historyId = e.id
            _state.update {
                it.copy(raw = e.rawText, result = e.cleanedText, style = Style.fromLabel(e.style), cleaning = false,
                    committed = "", partial = "", error = null, notice = null)
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
