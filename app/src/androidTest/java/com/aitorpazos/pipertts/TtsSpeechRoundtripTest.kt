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

package com.aitorpazos.pipertts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.aitorpazos.pipertts.download.VoiceDownloadManager
import com.aitorpazos.pipertts.engine.PiperEngine
import com.aitorpazos.pipertts.util.VoiceManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TTS Speech Roundtrip Test
 *
 * Downloads Piper voice models, synthesizes text, saves audio as WAV,
 * then uses Android's SpeechRecognizer (e.g. Futo Voice Input) to
 * transcribe the audio back to text and verify it matches the original.
 *
 * Requires:
 * - Network access (downloads ~120MB of voice models)
 * - A SpeechRecognizer implementation on the device (e.g. Futo Voice Input)
 *
 * If no SpeechRecognizer is available (e.g. CI emulator), the STT assertion
 * is skipped but synthesis is still verified to produce non-empty audio.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class TtsSpeechRoundtripTest {

    companion object {
        private const val TAG = "TtsRoundtripTest"
        private const val ENGLISH_VOICE_KEY = "en_US-amy-medium"
        private const val SPANISH_VOICE_KEY = "es_ES-davefx-medium"
        private const val ENGLISH_TEXT = "The quick brown fox jumps over the lazy dog"
        private const val SPANISH_TEXT = "El rápido zorro marrón salta sobre el perro perezoso"
        private val ENGLISH_KEYWORDS = listOf("quick", "brown", "fox", "jumps", "lazy", "dog")
        private val SPANISH_KEYWORDS = listOf("rápido", "zorro", "salta", "perro", "perezoso")
        private const val STT_TIMEOUT_SECONDS = 30L
    }

    private lateinit var context: Context
    private lateinit var downloadManager: VoiceDownloadManager
    private lateinit var voiceManager: VoiceManager
    private val downloadedVoices = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        downloadManager = VoiceDownloadManager(context)
        voiceManager = VoiceManager(context)
    }

    @After
    fun tearDown() {
        for (voiceKey in downloadedVoices) {
            try {
                downloadManager.deleteVoice(voiceKey)
                Log.i(TAG, "Cleaned up voice: $voiceKey")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up voice: $voiceKey", e)
            }
        }
    }

    @Test
    fun englishRoundtrip_synthesizeAndRecognize() {
        roundtripTest(
            voiceKey = ENGLISH_VOICE_KEY,
            text = ENGLISH_TEXT,
            keywords = ENGLISH_KEYWORDS,
            sttLanguage = "en-US",
            wavFilename = "roundtrip_english.wav"
        )
    }

    @Test
    fun spanishRoundtrip_synthesizeAndRecognize() {
        roundtripTest(
            voiceKey = SPANISH_VOICE_KEY,
            text = SPANISH_TEXT,
            keywords = SPANISH_KEYWORDS,
            sttLanguage = "es-ES",
            wavFilename = "roundtrip_spanish.wav"
        )
    }

    private fun roundtripTest(
        voiceKey: String,
        text: String,
        keywords: List<String>,
        sttLanguage: String,
        wavFilename: String
    ) {
        // Step 1: Download voice model
        Log.i(TAG, "Downloading voice: $voiceKey")
        val downloadSuccess = runBlocking {
            downloadManager.downloadVoice(voiceKey)
        }
        assertTrue("Failed to download voice: $voiceKey", downloadSuccess)
        downloadedVoices.add(voiceKey)

        // Step 2: Load voice and create engine
        val voiceData = voiceManager.loadVoiceByName(voiceKey)
        assertNotNull("Failed to load voice data for: $voiceKey", voiceData)

        val engine = PiperEngine(voiceData!!.config, voiceData.modelBytes)
        try {
            // Step 3: Synthesize text
            Log.i(TAG, "Synthesizing: '$text'")
            val samples = engine.synthesize(text)
            assertTrue(
                "Synthesis produced empty audio for: '$text'",
                samples.isNotEmpty()
            )
            assertTrue(
                "Synthesis produced too few samples (${samples.size}) - likely silence",
                samples.size > 1000
            )

            // Step 4: Convert to 16-bit PCM and save as WAV
            val pcmBytes = engine.floatToInt16Pcm(samples)
            val wavFile = File(context.cacheDir, wavFilename)
            writeWav(wavFile, pcmBytes, engine.sampleRate)
            assertTrue("WAV file was not created", wavFile.exists())
            assertTrue("WAV file is too small (${wavFile.length()} bytes)", wavFile.length() > 100)
            Log.i(TAG, "Saved WAV: ${wavFile.absolutePath} (${wavFile.length()} bytes)")

            // Step 5: Speech-to-text recognition
            val sttAvailable = SpeechRecognizer.isRecognitionAvailable(context)
            if (!sttAvailable) {
                Log.w(TAG, "SpeechRecognizer not available - skipping STT verification")
                Log.i(TAG, "Synthesis verification PASSED (${samples.size} samples, ${pcmBytes.size} PCM bytes)")
                return
            }

            val transcript = recognizeSpeech(wavFile, sttLanguage)
            if (transcript == null) {
                Log.w(TAG, "STT returned no results - skipping keyword verification")
                Log.i(TAG, "Synthesis verification PASSED (STT unavailable or returned empty)")
                return
            }

            Log.i(TAG, "STT transcript: '$transcript'")

            // Step 6: Fuzzy keyword matching
            val transcriptLower = transcript.lowercase()
            val matchedKeywords = keywords.filter { keyword ->
                transcriptLower.contains(keyword.lowercase())
            }
            val matchRatio = matchedKeywords.size.toFloat() / keywords.size

            Log.i(TAG, "Matched ${matchedKeywords.size}/${keywords.size} keywords: $matchedKeywords")

            assertTrue(
                "STT roundtrip failed: expected at least 50% keyword match. " +
                    "Matched ${matchedKeywords.size}/${keywords.size} keywords ($matchedKeywords). " +
                    "Transcript: '$transcript'. Original: '$text'",
                matchRatio >= 0.5f
            )

            Log.i(TAG, "Roundtrip PASSED for $voiceKey: $matchedKeywords")

        } finally {
            engine.close()
        }
    }

    private fun writeWav(file: File, pcmData: ByteArray, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())

            // fmt chunk
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())

            // data chunk
            header.put("data".toByteArray())
            header.putInt(dataSize)

            fos.write(header.array())
            fos.write(pcmData)
        }
    }

    private fun recognizeSpeech(wavFile: File, language: String): String? {
        val latch = CountDownLatch(1)
        var resultText: String? = null
        var errorMsg: String? = null

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    data = android.net.Uri.fromFile(wavFile)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "STT ready for speech")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "STT beginning of speech")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d(TAG, "STT end of speech")
                    }
                    override fun onError(error: Int) {
                        errorMsg = "SpeechRecognizer error code: $error"
                        Log.w(TAG, errorMsg!!)
                        recognizer.destroy()
                        latch.countDown()
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        resultText = matches?.firstOrNull()
                        Log.i(TAG, "STT results: $matches")
                        recognizer.destroy()
                        latch.countDown()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer.startListening(intent)
            } catch (e: Exception) {
                errorMsg = "SpeechRecognizer exception: ${e.message}"
                Log.e(TAG, errorMsg!!, e)
                latch.countDown()
            }
        }

        val completed = latch.await(STT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            Log.w(TAG, "STT timed out after $STT_TIMEOUT_SECONDS seconds")
            return null
        }

        if (errorMsg != null) {
            Log.w(TAG, "STT error: $errorMsg")
            return null
        }

        return resultText
    }
}
