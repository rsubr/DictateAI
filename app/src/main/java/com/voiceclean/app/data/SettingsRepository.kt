package com.voiceclean.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val llmBackend: String = "llama.cpp", // "llama.cpp" | "executorch-qnn" (stub)
    val threads: Int = com.voiceclean.app.diag.CpuInfo.defaultThreads(),
    val contextSize: Int = 4096,
    val autoCleanup: Boolean = true,
    val activeSttModel: String = "",
    val activeLlmModel: String = "",
)

class SettingsRepository(private val context: Context) {
    private object K {
        val backend = stringPreferencesKey("llm_backend")
        val threads = intPreferencesKey("threads")
        val ctx = intPreferencesKey("context_size")
        val auto = booleanPreferencesKey("auto_cleanup")
        val stt = stringPreferencesKey("active_stt_model")
        val llm = stringPreferencesKey("active_llm_model")
        val hfToken = stringPreferencesKey("hf_token")
    }

    /** Optional Hugging Face access token (for gated repos / rate limits). */
    val hfToken: Flow<String> = context.dataStore.data.map { it[K.hfToken] ?: "" }
    suspend fun setHfToken(v: String) = context.dataStore.edit { it[K.hfToken] = v.trim() }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            p[K.backend] ?: d.llmBackend, p[K.threads] ?: d.threads, p[K.ctx] ?: d.contextSize,
            p[K.auto] ?: d.autoCleanup, p[K.stt] ?: d.activeSttModel, p[K.llm] ?: d.activeLlmModel,
        )
    }

    suspend fun setBackend(v: String) = context.dataStore.edit { it[K.backend] = v }
    suspend fun setThreads(v: Int) = context.dataStore.edit { it[K.threads] = v }
    suspend fun setContextSize(v: Int) = context.dataStore.edit { it[K.ctx] = v }
    suspend fun setAutoCleanup(v: Boolean) = context.dataStore.edit { it[K.auto] = v }
    suspend fun setActiveSttModel(v: String) = context.dataStore.edit { it[K.stt] = v }
    suspend fun setActiveLlmModel(v: String) = context.dataStore.edit { it[K.llm] = v }
}
