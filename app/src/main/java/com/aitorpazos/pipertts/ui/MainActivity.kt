package com.aitorpazos.pipertts.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

        // Load available voices
        updateVoiceInfo()
    }

    private fun updateVoiceInfo() {
        lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) {
                voiceManager.listVoices()
            }
            binding.tvStatus.text = if (voices.isEmpty()) {
                "No voice models found.\nPlace .onnx + .onnx.json in assets/voices/"
            } else {
                "Available voices: ${voices.joinToString { "${it.name} (${it.locale})" }}"
            }
        }
    }

    private fun synthesizeAndPlay(text: String) {
        binding.btnSpeak.isEnabled = false
        binding.tvStatus.text = "Synthesizing..."

        lifecycleScope.launch {
            try {
                val pcmData = withContext(Dispatchers.IO) {
                    // Ensure engine is loaded
                    if (engine == null) {
                        val voiceData = voiceManager.loadVoice(java.util.Locale.US)
                            ?: throw Exception("No voice model available")
                        engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                    }

                    val activeEngine = engine!!
                    val samples = activeEngine.synthesize(text)
                    activeEngine.floatToInt16Pcm(samples)
                }

                if (pcmData.isEmpty()) {
                    binding.tvStatus.text = "No audio generated"
                    return@launch
                }

                playPcm(pcmData, engine!!.sampleRate)
                binding.tvStatus.text = "Playing (${pcmData.size / 2} samples)"

            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
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
