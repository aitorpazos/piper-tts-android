package com.aitorpazos.pipertts.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aitorpazos.pipertts.R

/**
 * Settings activity launched from system TTS settings.
 * Allows configuration of voice, speed, and pitch.
 */
class TtsSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_settings)
    }
}
