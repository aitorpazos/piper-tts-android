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

/**
 * Handles the android.speech.tts.engine.CHECK_TTS_DATA intent.
 *
 * Android's TTS framework sends this intent to verify which voice data
 * is installed. Without this activity, the engine will NOT appear in
 * Android's "Preferred engine" list.
 *
 * CRITICAL: Must ALWAYS return CHECK_VOICE_DATA_PASS with at least one
 * available locale. Any crash or missing result causes Android to hide
 * the engine from TTS settings.
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

        // Set a default result FIRST, before doing any work.
        // This ensures that even if something crashes below, Android still
        // gets a valid CHECK_VOICE_DATA_PASS result.
        val defaultResult = Intent()
        val defaultAvailable = ArrayList<String>()
        defaultAvailable.add("eng-USA")  // Android's expected format for English
        defaultResult.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, defaultAvailable)
        defaultResult.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, defaultResult)

        try {
            val available = ArrayList<String>()
            val unavailable = ArrayList<String>()

            try {
                val voiceManager = com.aitorpazos.pipertts.util.VoiceManager(this)
                val voices = voiceManager.listVoices()

                for (voice in voices) {
                    // Android expects locale strings in the format used by Locale.toString()
                    // e.g., "en_US", "es_ES", "de_DE"
                    val lang = voice.locale.language
                    val country = voice.locale.country
                    available.add(if (country.isNotEmpty()) "${lang}_${country}" else lang)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error listing voices for CHECK_TTS_DATA", e)
            }

            // Always report at least English as available.
            if (available.isEmpty()) {
                available.add("eng-USA")
            }

            Log.i(TAG, "CHECK_TTS_DATA: available=$available")

            val result = Intent()
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)

            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        } catch (e: Exception) {
            // Even on total failure, the default result set above ensures
            // Android gets CHECK_VOICE_DATA_PASS
            Log.e(TAG, "Unexpected error in CheckVoiceData", e)
        }

        finish()
    }
}
