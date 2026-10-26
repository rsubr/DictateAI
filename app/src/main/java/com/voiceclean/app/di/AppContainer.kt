package com.voiceclean.app.di

import android.content.Context
import androidx.room.Room
import com.voiceclean.app.data.AppDatabase
import com.voiceclean.app.data.HistoryRepository
import com.voiceclean.app.data.SettingsRepository
import com.voiceclean.app.llm.FakeLlmEngine
import com.voiceclean.app.llm.LlmEngine
import com.voiceclean.app.stt.FakeSttEngine
import com.voiceclean.app.stt.SttEngine
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/** Manual DI. Phase B/C swap the fake engines for real ones here. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { Room.databaseBuilder(appContext, AppDatabase::class.java, "voiceclean.db").addMigrations(com.voiceclean.app.data.MIGRATION_1_2).build() }

    val historyRepository by lazy { HistoryRepository(db.transcriptionDao()) }
    val settingsRepository by lazy { SettingsRepository(appContext) }
    val httpClient by lazy { OkHttpClient() }
    // Engines: real native engine wrapped so we fall back to the fake until a model has loaded OK.
    // Callers must `load(path, ...)` the active model; load failures leave the fake in place (check lastError).
    val sttEngine: SttEngine by lazy {
        com.voiceclean.app.stt.FallbackSttEngine(com.voiceclean.app.stt.WhisperEngine(appContext), FakeSttEngine())
    }
    /** Picks the LLM engine for a settings backend id ("llama.cpp" | "executorch-qnn"). */
    fun createLlmEngine(backendId: String): LlmEngine = com.voiceclean.app.llm.FallbackLlmEngine(
        when (backendId) {
            "executorch-qnn" -> com.voiceclean.app.llm.ExecuTorchQnnEngine()
            else -> com.voiceclean.app.llm.LlamaCppEngine(appContext)
        }, FakeLlmEngine(),
    )
    val llmEngine: LlmEngine by lazy { createLlmEngine("llama.cpp") }
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    /** Loads the real engines lazily on the active models (see [RealEngineProvider]). */
    val engineProvider: EngineProvider by lazy {
        RealEngineProvider(appContext, modelRepository, settingsRepository, sttEngine, llmEngine, appScope)
    }
    val diagnostics by lazy { com.voiceclean.app.diag.Diagnostics(appContext, this, appScope) }

    // --- Models (M3) ---
    val downloadManager by lazy {
        com.voiceclean.app.models.DownloadManager(
            httpClient.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build(),
            tokenProvider = { settingsRepository.hfToken.first().ifBlank { null } },
        )
    }
    val modelRepository by lazy {
        com.voiceclean.app.models.ModelRepository(appContext, settingsRepository, downloadManager, httpClient)
    }
}
