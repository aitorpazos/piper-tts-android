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

/**
 * Handles the android.speech.tts.engine.INSTALL_TTS_DATA intent.
 *
 * When Android determines voice data is missing (CHECK_TTS_DATA returned FAIL),
 * it fires this intent so the engine can install the required data.
 * We redirect to our VoiceListActivity where the user can download voices.
 */
class InstallVoiceData : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Open our Voice Manager so the user can download voices
        val voiceListIntent = Intent(this, VoiceListActivity::class.java)
        startActivity(voiceListIntent)
        finish()
    }
}
