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
import okhttp3.OkHttpClient

/** Manual DI. Phase B/C swap the fake engines for real ones here. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { Room.databaseBuilder(appContext, AppDatabase::class.java, "voiceclean.db").build() }

    val historyRepository by lazy { HistoryRepository(db.transcriptionDao()) }
    val settingsRepository by lazy { SettingsRepository(appContext) }
    val httpClient by lazy { OkHttpClient() }
    val sttEngine: SttEngine by lazy { FakeSttEngine() }
    val llmEngine: LlmEngine by lazy { FakeLlmEngine() }
}
