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
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.aitorpazos.pipertts.download.VoiceDownloadManager
import com.aitorpazos.pipertts.engine.PiperEngine
import com.aitorpazos.pipertts.util.VoiceManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * TTS Speech Roundtrip Test
 *
 * Downloads Piper voice models, synthesizes text, saves audio as WAV,
 * then uses Vosk (offline STT) to transcribe the audio back to text
 * and verify it matches the original with ≥90% keyword accuracy.
 *
 * Requires network access (downloads voice models + Vosk STT models).
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
        private val SPANISH_KEYWORDS = listOf("rápido", "zorro", "marrón", "salta", "perro", "perezoso")
        private const val MATCH_THRESHOLD = 0.9f

        // Small Vosk models for STT
        private const val VOSK_EN_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val VOSK_ES_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    }

    private lateinit var context: Context
    private lateinit var downloadManager: VoiceDownloadManager
    private lateinit var voiceManager: VoiceManager
    private val downloadedVoices = mutableListOf<String>()
    private val tempDirs = mutableListOf<File>()

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
        for (dir in tempDirs) {
            try {
                dir.deleteRecursively()
                Log.i(TAG, "Cleaned up temp dir: ${dir.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up temp dir: ${dir.absolutePath}", e)
            }
        }
    }

    @Test
    fun englishRoundtrip_synthesizeAndRecognize() {
        roundtripTest(
            voiceKey = ENGLISH_VOICE_KEY,
            text = ENGLISH_TEXT,
            keywords = ENGLISH_KEYWORDS,
            voskModelUrl = VOSK_EN_MODEL_URL,
            wavFilename = "roundtrip_english.wav"
        )
    }

    @Test
    fun spanishRoundtrip_synthesizeAndRecognize() {
        roundtripTest(
            voiceKey = SPANISH_VOICE_KEY,
            text = SPANISH_TEXT,
            keywords = SPANISH_KEYWORDS,
            voskModelUrl = VOSK_ES_MODEL_URL,
            wavFilename = "roundtrip_spanish.wav"
        )
    }

    private fun roundtripTest(
        voiceKey: String,
        text: String,
        keywords: List<String>,
        voskModelUrl: String,
        wavFilename: String
    ) {
        // Step 1: Download Piper voice model
        Log.i(TAG, "Downloading Piper voice: $voiceKey")
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

            // Verify audio is not all silence
            val maxAmplitude = samples.maxOf { kotlin.math.abs(it) }
            assertTrue(
                "Audio appears to be silence (max amplitude: $maxAmplitude)",
                maxAmplitude > 0.01f
            )

            // Step 4: Convert to 16-bit PCM and save as WAV
            val pcmBytes = engine.floatToInt16Pcm(samples)
            val wavFile = File(context.cacheDir, wavFilename)
            writeWav(wavFile, pcmBytes, engine.sampleRate)
            assertTrue("WAV file was not created", wavFile.exists())
            assertTrue("WAV file is too small (${wavFile.length()} bytes)", wavFile.length() > 100)
            Log.i(TAG, "Saved WAV: ${wavFile.absolutePath} (${wavFile.length()} bytes, sampleRate=${engine.sampleRate})")

            // Step 5: Download Vosk STT model
            Log.i(TAG, "Downloading Vosk model from: $voskModelUrl")
            val modelDir = downloadVoskModel(voskModelUrl)
            tempDirs.add(modelDir)

            // Step 6: Transcribe with Vosk
            val transcript = transcribeWithVosk(wavFile, modelDir, engine.sampleRate)
            assertNotNull("Vosk returned no transcription", transcript)
            Log.i(TAG, "Vosk transcript: '$transcript'")

            // Step 7: Keyword matching at 90% threshold
            val transcriptLower = transcript!!.lowercase()
            val matchedKeywords = keywords.filter { keyword ->
                transcriptLower.contains(keyword.lowercase())
            }
            val matchRatio = matchedKeywords.size.toFloat() / keywords.size

            Log.i(TAG, "Matched ${matchedKeywords.size}/${keywords.size} keywords (${(matchRatio * 100).toInt()}%): $matchedKeywords")
            val missedKeywords = keywords.filter { it.lowercase() !in transcriptLower.lowercase() }
            if (missedKeywords.isNotEmpty()) {
                Log.w(TAG, "Missed keywords: $missedKeywords")
            }

            assertTrue(
                "STT roundtrip failed: expected at least ${(MATCH_THRESHOLD * 100).toInt()}% keyword match. " +
                    "Matched ${matchedKeywords.size}/${keywords.size} keywords ($matchedKeywords). " +
                    "Missed: $missedKeywords. " +
                    "Transcript: '$transcript'. Original: '$text'",
                matchRatio >= MATCH_THRESHOLD
            )

            Log.i(TAG, "Roundtrip PASSED for $voiceKey: ${(matchRatio * 100).toInt()}% match")

        } finally {
            engine.close()
        }
    }

    /**
     * Transcribe a WAV file using Vosk offline STT.
     * Feeds the audio in chunks to Vosk's Recognizer and returns the final text.
     */
    private fun transcribeWithVosk(wavFile: File, modelDir: File, sampleRate: Int): String? {
        val model = Model(modelDir.absolutePath)
        // Vosk works best at 16000 Hz; if our sample rate differs, we still try
        val recognizer = Recognizer(model, sampleRate.toFloat())

        try {
            FileInputStream(wavFile).use { fis ->
                // Skip WAV header (44 bytes)
                val header = ByteArray(44)
                val headerRead = fis.read(header)
                if (headerRead < 44) return null

                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    recognizer.acceptWaveForm(buffer, bytesRead)
                }
            }

            val resultJson = recognizer.finalResult
            Log.d(TAG, "Vosk raw result: $resultJson")

            val json = JSONObject(resultJson)
            return json.optString("text", "").ifEmpty { null }
        } finally {
            recognizer.close()
            model.close()
        }
    }

    /**
     * Download and extract a Vosk model zip to a temp directory.
     * Returns the path to the extracted model directory.
     */
    private fun downloadVoskModel(modelUrl: String): File {
        val modelsBaseDir = File(context.cacheDir, "vosk-models")
        modelsBaseDir.mkdirs()

        // Use URL filename as cache key
        val zipName = modelUrl.substringAfterLast("/")
        val modelName = zipName.removeSuffix(".zip")
        val modelDir = File(modelsBaseDir, modelName)

        // If already extracted, reuse
        if (modelDir.exists() && modelDir.isDirectory && modelDir.list()?.isNotEmpty() == true) {
            Log.i(TAG, "Reusing cached Vosk model: ${modelDir.absolutePath}")
            return modelDir
        }

        // Download zip
        val zipFile = File(modelsBaseDir, zipName)
        Log.i(TAG, "Downloading Vosk model: $modelUrl")
        URL(modelUrl).openStream().use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Downloaded: ${zipFile.length()} bytes")

        // Extract zip
        modelDir.mkdirs()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Vosk zips typically have a top-level directory; strip it
                val entryName = entry.name
                val relativePath = if (entryName.contains("/")) {
                    entryName.substringAfter("/")
                } else {
                    entryName
                }

                if (relativePath.isNotEmpty()) {
                    val outFile = File(modelDir, relativePath)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Clean up zip
        zipFile.delete()
        Log.i(TAG, "Extracted Vosk model to: ${modelDir.absolutePath}")
        return modelDir
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
}
