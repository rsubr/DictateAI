package com.voiceclean.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voiceclean.app.ui.VoiceCleanNav
import com.voiceclean.app.ui.theme.VoiceCleanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoiceCleanApp).container
        setContent { VoiceCleanTheme { VoiceCleanNav(container) } }
    }
}
