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
        // load the speech model (+ warm-up decode) early so the first dictation is not cold; never loads the LLM
        container.engineProvider.warmUpStt()
    }
}
