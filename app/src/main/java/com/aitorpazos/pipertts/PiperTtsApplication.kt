package com.aitorpazos.pipertts

import android.app.Application
import android.util.Log

class PiperTtsApplication : Application() {
    companion object {
        private const val TAG = "PiperTtsApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Piper TTS Application started")
    }
}
