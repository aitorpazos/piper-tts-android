/*
 * Piper TTS for Android
 * Copyright (C) 2026 Aitor Pazos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.aitorpazos.pipertts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.aitorpazos.pipertts.engine.PhonemeConverter
import com.aitorpazos.pipertts.model.PiperVoiceConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.zip.ZipInputStream

/**
 * Pure JVM roundtrip test: TTS synthesis with Piper (ONNX Runtime) -> STT with Vosk.
 *
 * Downloads voice models and Vosk STT models from the internet.
 * Runs entirely on JVM — no Android emulator or device needed.
 *
 * Requires >=90% keyword match between original text and STT transcription.
 */
class TtsSpeechRoundtripTest {

    companion object {
        private const val KEYWORD_THRESHOLD = 0.90

        // Piper voice model URLs (HuggingFace)
        private const val EN_MODEL_URL =
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx"
        private const val EN_CONFIG_URL =
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json"
        private const val ES_MODEL_URL =
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx"
        private const val ES_CONFIG_URL =
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/es_ES-davefx-medium.onnx.json"

        // Vosk STT model URLs
        private const val VOSK_EN_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val VOSK_ES_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"

        // ~100-word texts with punctuation marks for thorough roundtrip testing
        private const val EN_TEXT = "The quick brown fox jumps over the lazy dog in the park. " +
            "Meanwhile, the curious cat watches from the window, wondering if it should go outside. " +
            "Birds are singing loudly; the morning sun is warm and bright. " +
            "\"What a beautiful day!\" said the old farmer, walking slowly toward the river. " +
            "He carried a basket full of apples, oranges, and fresh bread. " +
            "The children were playing near the bridge — laughing, running, and shouting with joy. " +
            "Suddenly, a loud thunder echoed across the valley: the storm was approaching fast. " +
            "Everyone hurried back home, hoping the rain wouldn't last too long."

        private const val ES_TEXT = "El rápido zorro marrón salta sobre el perro perezoso en la plaza grande. " +
            "Mientras tanto, el gato curioso observa desde la ventana, preguntándose si debería salir. " +
            "Los pájaros cantan con fuerza; el sol de la mañana es cálido y brillante. " +
            "\"¡Qué día tan hermoso!\" dijo el viejo granjero, caminando despacio hacia el río. " +
            "Llevaba una cesta llena de manzanas, naranjas y pan fresco. " +
            "Los niños jugaban cerca del puente — riendo, corriendo y gritando de alegría. " +
            "De repente, un fuerte trueno resonó por todo el valle: la tormenta se acercaba rápido. " +
            "Todos corrieron a casa, esperando que la lluvia no durara demasiado."

        private val EN_KEYWORDS = listOf(
            "fox", "jumps", "lazy", "dog", "park",
            "cat", "watches", "window", "outside",
            "birds", "singing", "morning", "sun", "warm", "bright",
            "beautiful", "day", "farmer", "walking", "river",
            "basket", "apples", "oranges", "bread",
            "children", "playing", "bridge", "laughing", "running",
            "thunder", "valley", "storm", "approaching",
            "hurried", "home", "rain"
        )
        private val ES_KEYWORDS = listOf(
            "zorro", "salta", "perro", "perezoso", "plaza",
            "gato", "curioso", "ventana", "salir",
            "pájaros", "cantan", "sol", "mañana", "cálido", "brillante",
            "hermoso", "granjero", "caminando", "río",
            "cesta", "manzanas", "naranjas", "pan",
            "niños", "jugaban", "puente", "riendo", "corriendo",
            "trueno", "valle", "tormenta", "acercaba",
            "corrieron", "casa", "lluvia"
        )
    }

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "piper-tts-test-cache")
        cacheDir.mkdirs()
    }

    @After
    fun tearDown() {
        // Keep cache for faster re-runs; CI will discard anyway
    }

    @Test
    fun `english roundtrip - synthesize and recognize`() {
        val modelBytes = downloadFile(EN_MODEL_URL, "en_US-amy-medium.onnx")
        val configJson = String(downloadFile(EN_CONFIG_URL, "en_US-amy-medium.onnx.json"))
        val config = PiperVoiceConfig.fromJson(configJson)

        val audio = synthesize(modelBytes, config, EN_TEXT)
        assertTrue("Audio output must not be empty", audio.isNotEmpty())
        assertFalse("Audio must not be silence", audio.all { it == 0.0f })

        val wavBytes = floatsToWav(audio, config.audio.sampleRate)
        val voskModelDir = downloadAndExtractVoskModel(VOSK_EN_URL, "vosk-en")
        val transcript = recognizeWithVosk(wavBytes, config.audio.sampleRate, voskModelDir)

        println("EN original:   $EN_TEXT")
        println("EN transcript: $transcript")

        val matchRatio = keywordMatchRatio(transcript, EN_KEYWORDS)
        println("EN keyword match: ${(matchRatio * 100).toInt()}% (threshold: ${(KEYWORD_THRESHOLD * 100).toInt()}%)")
        assertTrue(
            "English keyword match ${(matchRatio * 100).toInt()}% is below ${(KEYWORD_THRESHOLD * 100).toInt()}% threshold",
            matchRatio >= KEYWORD_THRESHOLD
        )
    }

    @Test
    fun `spanish roundtrip - synthesize and recognize`() {
        val modelBytes = downloadFile(ES_MODEL_URL, "es_ES-davefx-medium.onnx")
        val configJson = String(downloadFile(ES_CONFIG_URL, "es_ES-davefx-medium.onnx.json"))
        val config = PiperVoiceConfig.fromJson(configJson)

        val audio = synthesize(modelBytes, config, ES_TEXT)
        assertTrue("Audio output must not be empty", audio.isNotEmpty())
        assertFalse("Audio must not be silence", audio.all { it == 0.0f })

        val wavBytes = floatsToWav(audio, config.audio.sampleRate)
        val voskModelDir = downloadAndExtractVoskModel(VOSK_ES_URL, "vosk-es")
        val transcript = recognizeWithVosk(wavBytes, config.audio.sampleRate, voskModelDir)

        println("ES original:   $ES_TEXT")
        println("ES transcript: $transcript")

        val matchRatio = keywordMatchRatio(transcript, ES_KEYWORDS)
        println("ES keyword match: ${(matchRatio * 100).toInt()}% (threshold: ${(KEYWORD_THRESHOLD * 100).toInt()}%)")
        assertTrue(
            "Spanish keyword match ${(matchRatio * 100).toInt()}% is below ${(KEYWORD_THRESHOLD * 100).toInt()}% threshold",
            matchRatio >= KEYWORD_THRESHOLD
        )
    }

    // -- TTS synthesis (pure ONNX Runtime JVM, no android.util.Log) --

    /**
     * Synthesize speech using Piper ONNX model.
     *
     * For espeak-based models (phoneme_type == "espeak"), uses espeak-ng CLI
     * to convert text to IPA phonemes before mapping to phoneme IDs.
     * Falls back to character-based mapping for text-type models.
     */
    private fun synthesize(modelBytes: ByteArray, config: PiperVoiceConfig, text: String): FloatArray {
        val phonemeIdMap = config.phonemeIdMap ?: emptyMap()
        val phonemeIds = if (config.phonemeType == "espeak") {
            val espeakVoice = config.espeak?.voice ?: "en-us"
            textToPhonemeIdsViaEspeak(text, espeakVoice, phonemeIdMap)
        } else {
            PhonemeConverter(phonemeIdMap).textToPhonemeIds(text)
        }
        if (phonemeIds.isEmpty()) return FloatArray(0)

        val ortEnv = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = ortEnv.createSession(modelBytes, sessionOptions)

        val inputLength = phonemeIds.size.toLong()
        val inputTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(phonemeIds), longArrayOf(1, inputLength)
        )
        val inputLengthsTensor = OnnxTensor.createTensor(
            ortEnv, LongBuffer.wrap(longArrayOf(inputLength)), longArrayOf(1)
        )
        val noiseScale = config.inference?.noiseScale ?: 0.667f
        val lengthScale = config.inference?.lengthScale ?: 1.0f
        val noiseW = config.inference?.noiseW ?: 0.8f
        val scalesTensor = OnnxTensor.createTensor(
            ortEnv, FloatBuffer.wrap(floatArrayOf(noiseScale, lengthScale, noiseW)), longArrayOf(3)
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "input_lengths" to inputLengthsTensor,
            "scales" to scalesTensor
        )

        try {
            val result = session.run(inputs)
            val outputTensor = result[0] as OnnxTensor
            val rawOutput = outputTensor.floatBuffer
            val samples = FloatArray(rawOutput.remaining())
            rawOutput.get(samples)
            return samples
        } finally {
            inputs.values.forEach { it.close() }
            session.close()
        }
    }

    /**
     * Convert text to phoneme IDs using espeak-ng for IPA phonemization.
     * Runs `espeak-ng --ipa -q -v <voice> "<text>"` and maps each IPA character
     * to its phoneme ID using the model's phoneme_id_map.
     */
    private fun textToPhonemeIdsViaEspeak(
        text: String,
        espeakVoice: String,
        phonemeIdMap: Map<String, List<Int>>
    ): LongArray {
        // Run espeak-ng to get IPA phonemes
        val process = ProcessBuilder(
            "espeak-ng", "--ipa", "-q", "-v", espeakVoice, text
        ).redirectErrorStream(true).start()
        val ipaOutput = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()

        println("espeak-ng IPA output: $ipaOutput")

        val ids = mutableListOf<Long>()
        val padId = phonemeIdMap["_"]?.firstOrNull()?.toLong() ?: 0L
        val bosIds = phonemeIdMap["^"]?.map { it.toLong() }
        val eosIds = phonemeIdMap["$"]?.map { it.toLong() }

        // BOS
        bosIds?.let { ids.addAll(it) }
        ids.add(padId)

        // Process each IPA character/symbol
        for (char in ipaOutput) {
            val charStr = char.toString()
            val charIds = phonemeIdMap[charStr]?.map { it.toLong() }
            if (charIds != null) {
                ids.addAll(charIds)
                ids.add(padId)
            }
            // Skip characters not in the phoneme map (stress marks, etc.)
        }

        // EOS
        eosIds?.let { ids.addAll(it) }
        ids.add(padId)

        return ids.toLongArray()
    }

    // -- Audio conversion --

    private fun floatsToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        // Convert float samples to 16-bit PCM
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val intVal = (clamped * 32767).toInt().toShort()
            pcm[i * 2] = (intVal.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (intVal.toInt() shr 8 and 0xFF).toByte()
        }

        // Build WAV file manually (no javax.sound.sampled dependency)
        val numChannels: Short = 1
        val bitsPerSample: Short = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign: Short = (numChannels * bitsPerSample / 8).toShort()
        val dataSize = pcm.size
        val chunkSize = 36 + dataSize

        val baos = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray())

        // fmt sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16)                    // sub-chunk size
        header.putShort(1)                   // PCM format
        header.putShort(numChannels)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample)

        // data sub-chunk
        header.put("data".toByteArray())
        header.putInt(dataSize)

        baos.write(header.array())
        baos.write(pcm)
        return baos.toByteArray()
    }

    // -- STT with Vosk --

    private fun recognizeWithVosk(wavBytes: ByteArray, sampleRate: Int, modelDir: File): String {
        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, sampleRate.toFloat())

        // Skip WAV header (44 bytes) and feed raw PCM
        val pcmData = wavBytes.copyOfRange(44, wavBytes.size)
        val chunkSize = 4096
        var offset = 0
        while (offset < pcmData.size) {
            val end = minOf(offset + chunkSize, pcmData.size)
            val chunk = pcmData.copyOfRange(offset, end)
            recognizer.acceptWaveForm(chunk, chunk.size)
            offset = end
        }

        val finalResult = recognizer.finalResult
        recognizer.close()
        model.close()

        // Vosk returns JSON: {"text": "the quick brown fox ..."}
        return try {
            JSONObject(finalResult).optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }

    // -- Keyword matching --

    private fun keywordMatchRatio(transcript: String, keywords: List<String>): Double {
        val lower = transcript.lowercase()
            .replace("\u00e1", "a").replace("\u00e9", "e").replace("\u00ed", "i")
            .replace("\u00f3", "o").replace("\u00fa", "u")
        val matched = keywords.count { keyword ->
            val normalized = keyword.lowercase()
                .replace("\u00e1", "a").replace("\u00e9", "e").replace("\u00ed", "i")
                .replace("\u00f3", "o").replace("\u00fa", "u")
            lower.contains(normalized)
        }
        return matched.toDouble() / keywords.size
    }

    // -- Download helpers --

    private fun downloadFile(url: String, cacheFileName: String): ByteArray {
        val cached = File(cacheDir, cacheFileName)
        if (cached.exists() && cached.length() > 1000) {
            return cached.readBytes()
        }
        println("Downloading $url ...")
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        cached.writeBytes(bytes)
        return bytes
    }

    private fun downloadAndExtractVoskModel(url: String, cacheDirName: String): File {
        val modelDir = File(cacheDir, cacheDirName)
        if (modelDir.exists() && modelDir.listFiles()?.any { it.isDirectory } == true) {
            val inner = modelDir.listFiles()?.firstOrNull { it.isDirectory }
            if (inner != null && File(inner, "conf/model.conf").exists()) {
                return inner
            }
        }

        modelDir.mkdirs()
        val zipFile = File(cacheDir, "$cacheDirName.zip")
        if (!zipFile.exists() || zipFile.length() < 1000) {
            println("Downloading $url ...")
            URI(url).toURL().openStream().use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        println("Extracting $zipFile ...")
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(modelDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val inner = modelDir.listFiles()?.firstOrNull { it.isDirectory }
        return inner ?: modelDir
    }
}
