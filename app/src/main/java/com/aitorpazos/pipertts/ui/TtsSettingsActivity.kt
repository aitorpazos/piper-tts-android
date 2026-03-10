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

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aitorpazos.pipertts.R
import com.aitorpazos.pipertts.databinding.ActivityTtsSettingsBinding
import com.aitorpazos.pipertts.util.VoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings activity launched from system TTS settings.
 * Shows installed voices and allows managing them.
 */
class TtsSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTtsSettingsBinding
    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTtsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceManager = VoiceManager(this)

        binding.btnManageVoices.setOnClickListener {
            startActivity(Intent(this, VoiceListActivity::class.java))
        }

        binding.btnOpenApp.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        refreshVoiceInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshVoiceInfo()
    }

    private fun refreshVoiceInfo() {
        lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) {
                voiceManager.listVoices()
            }

            if (voices.isEmpty()) {
                binding.tvVoiceInfo.text = getString(R.string.no_voices_installed)
            } else {
                val voiceLines = voices.joinToString("\n") { voice ->
                    "• ${voice.locale.displayLanguage} (${voice.locale.displayCountry}) — ${voice.name}"
                }
                binding.tvVoiceInfo.text = getString(
                    R.string.installed_voices_summary,
                    voices.size
                ) + "\n" + voiceLines
            }
        }
    }
}
