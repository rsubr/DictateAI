package com.voiceclean.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import com.voiceclean.app.VoiceCleanApp
import com.voiceclean.app.models.CatalogModel
import com.voiceclean.app.models.DownloadService
import com.voiceclean.app.models.ModelEntry
import com.voiceclean.app.models.ModelKind
import com.voiceclean.app.models.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: ModelRepository = (app as VoiceCleanApp).container.modelRepository

    val entries: StateFlow<List<ModelEntry>> =
        repo.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val storageUsed: StateFlow<Long> = repo.storageUsedBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val token: StateFlow<String> = repo.hfToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _customBusy = MutableStateFlow(false)
    val customBusy: StateFlow<Boolean> = _customBusy.asStateFlow()
    private val _customError = MutableStateFlow<String?>(null)
    val customError: StateFlow<String?> = _customError.asStateFlow()

    fun consumeMessage() { _message.value = null }
    fun clearCustomError() { _customError.value = null }

    fun download(m: CatalogModel) {
        repo.dismissError(m)
        repo.download(m)
        DownloadService.ensureRunning(getApplication())
    }
    fun pause(m: CatalogModel) = repo.pause(m)
    fun cancel(m: CatalogModel) = repo.cancel(m)
    fun select(m: CatalogModel) = viewModelScope.launch { repo.select(m) }
    fun delete(m: CatalogModel) = viewModelScope.launch { repo.delete(m); _message.value = "Deleted ${m.displayName}" }
    fun saveToken(t: String) = viewModelScope.launch { repo.setHfToken(t); _message.value = "Token saved" }

    fun addCustom(input: String, file: String, kind: ModelKind, onDone: () -> Unit) {
        viewModelScope.launch {
            _customBusy.value = true; _customError.value = null
            repo.addCustom(input, file, kind)
                .onSuccess { _message.value = "Added ${it.displayName}"; onDone() }
                .onFailure { _customError.value = it.message ?: "Validation failed" }
            _customBusy.value = false
        }
    }
}
