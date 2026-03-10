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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aitorpazos.pipertts.R
import com.aitorpazos.pipertts.databinding.ActivityMainBinding
import com.aitorpazos.pipertts.engine.PiperEngine
import com.aitorpazos.pipertts.util.VoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceManager: VoiceManager
    private var engine: PiperEngine? = null
    private var audioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceManager = VoiceManager(this)

        // === Installed Voices section ===
        binding.btnManageVoices.setOnClickListener {
            startActivity(Intent(this, VoiceListActivity::class.java))
        }

        // === TTS Engine section ===
        binding.btnOpenTtsSettings.setOnClickListener {
            try {
                // Open Android TTS settings directly
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                // Fallback to general settings
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, R.string.tts_settings_not_found, Toast.LENGTH_LONG).show()
                }
            }
        }

        // === Test TTS section ===
        binding.btnSpeak.setOnClickListener {
            val text = binding.etInput.text?.toString() ?: ""
            if (text.isNotBlank()) {
                synthesizeAndPlay(text)
            } else {
                Toast.makeText(this, "Enter some text first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStop.setOnClickListener {
            stopPlayback()
        }

        // Load info
        refreshInstalledVoices()
        checkTtsEngineStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshInstalledVoices()
        checkTtsEngineStatus()
    }

    private fun refreshInstalledVoices() {
        lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) {
                voiceManager.listVoices()
            }
            if (voices.isEmpty()) {
                binding.tvInstalledVoices.text = getString(R.string.no_voices_installed)
                // Disable TTS test when no voices installed
                binding.btnSpeak.isEnabled = false
                binding.tvStatus.text = "Download a voice first to test TTS"
            } else {
                binding.btnSpeak.isEnabled = true
                binding.tvStatus.text = getString(R.string.status_ready)
                val voiceLines = voices.joinToString("\n") { voice ->
                    "• ${voice.locale.displayLanguage} (${voice.locale.displayCountry}) — ${voice.name}"
                }
                binding.tvInstalledVoices.text = getString(
                    R.string.installed_voices_summary,
                    voices.size
                ) + "\n" + voiceLines
            }
        }
    }

    private fun checkTtsEngineStatus() {
        lifecycleScope.launch {
            try {
                // Check if our engine is the default
                val defaultEngine = Settings.Secure.getString(
                    contentResolver,
                    "tts_default_synth"
                )
                val isDefault = defaultEngine == packageName

                if (isDefault) {
                    binding.tvEngineStatus.text = getString(R.string.engine_status_active)
                    binding.tvEngineStatus.setTextColor(getColor(R.color.engine_active))
                } else {
                    binding.tvEngineStatus.text = getString(
                        R.string.engine_status_inactive,
                        defaultEngine ?: getString(R.string.unknown)
                    )
                }
            } catch (e: Exception) {
                binding.tvEngineStatus.text = getString(R.string.engine_status_checking)
            }
        }
    }

    private fun synthesizeAndPlay(text: String) {
        binding.btnSpeak.isEnabled = false
        binding.tvStatus.text = getString(R.string.status_synthesizing)

        lifecycleScope.launch {
            try {
                val pcmData = withContext(Dispatchers.IO) {
                    // Ensure engine is loaded
                    if (engine == null) {
                        val voiceData = voiceManager.loadVoice(java.util.Locale.US)
                            ?: throw Exception("No voice model available. Tap 'Manage' to download one.")
                        engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                    }

                    val activeEngine = engine!!
                    val samples = activeEngine.synthesize(text)
                    activeEngine.floatToInt16Pcm(samples)
                }

                if (pcmData.isEmpty()) {
                    binding.tvStatus.text = getString(R.string.status_no_audio)
                    return@launch
                }

                playPcm(pcmData, engine!!.sampleRate)
                binding.tvStatus.text = getString(R.string.status_playing, pcmData.size / 2)

            } catch (e: Exception) {
                binding.tvStatus.text = getString(R.string.status_error, e.message ?: "Unknown")
            } finally {
                binding.btnSpeak.isEnabled = true
            }
        }
    }

    private fun playPcm(pcmData: ByteArray, sampleRate: Int) {
        stopPlayback()

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcmData, 0, pcmData.size)
        audioTrack?.play()
    }

    private fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        engine?.close()
    }
}
