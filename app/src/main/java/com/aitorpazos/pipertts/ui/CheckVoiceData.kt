/*
 * Piper TTS for Android
 * Copyright (C) 2026 Aitor Pazos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.aitorpazos.pipertts.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import com.aitorpazos.pipertts.util.VoiceManager

/**
 * Handles the android.speech.tts.engine.CHECK_TTS_DATA intent.
 *
 * Android's TTS framework sends this intent to verify which voice data
 * is installed. Without this activity, the engine will NOT appear in
 * Android's "Preferred engine" list.
 *
 * CRITICAL: Must always return CHECK_VOICE_DATA_PASS so the engine appears
 * in Android TTS settings. If no voices are installed yet, we still report
 * English as available — the user can download voices via Voice Manager.
 *
 * Must return:
 * - TextToSpeech.Engine.CHECK_VOICE_DATA_PASS if voice data is available
 * - EXTRA_AVAILABLE_VOICES: ArrayList<String> of available locale strings
 * - EXTRA_UNAVAILABLE_VOICES: ArrayList<String> of unavailable locale strings
 */
class CheckVoiceData : Activity() {

    companion object {
        private const val TAG = "CheckVoiceData"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()

        try {
            val voiceManager = VoiceManager(this)
            val voices = voiceManager.listVoices()

            for (voice in voices) {
                try {
                    val lang = voice.locale.isO3Language
                    val country = voice.locale.isO3Country
                    available.add(if (country.isNotEmpty()) "$lang-$country" else lang)
                } catch (e: Exception) {
                    // Fallback to 2-letter codes
                    val lang = voice.locale.language
                    val country = voice.locale.country
                    available.add(if (country.isNotEmpty()) "$lang-$country" else lang)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing voices for CHECK_TTS_DATA", e)
        }

        // Always report at least English as available.
        // This ensures the engine appears in Android TTS settings even before
        // the user downloads any voice. The engine will prompt for download
        // when synthesis is first attempted.
        if (available.isEmpty()) {
            available.add("eng-USA")
        }

        Log.i(TAG, "CHECK_TTS_DATA: available=$available")

        val result = Intent()
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)

        // Always return PASS so the engine is visible in Android settings
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}
