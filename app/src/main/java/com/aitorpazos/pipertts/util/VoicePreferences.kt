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

package com.aitorpazos.pipertts.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's active voice selection.
 *
 * The selected voice key is stored in SharedPreferences and used by both
 * the in-app test TTS and the system TTS service.
 */
class VoicePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "piper_tts_prefs"
        private const val KEY_ACTIVE_VOICE = "active_voice_key"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the currently selected active voice key, or null if none is set.
     */
    var activeVoiceKey: String?
        get() = prefs.getString(KEY_ACTIVE_VOICE, null)
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_VOICE, value).apply()
        }

    /**
     * Clear the active voice selection.
     */
    fun clearActiveVoice() {
        prefs.edit().remove(KEY_ACTIVE_VOICE).apply()
    }
}
