package com.voiceclean.app

import android.app.Application
import com.voiceclean.app.di.AppContainer

class VoiceCleanApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.voiceclean.app.diag.DebugLog.init(this)
        container = AppContainer(this)
    }
}
