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

        // ~500-word texts with punctuation marks for thorough roundtrip testing
        private const val EN_TEXT = "The quick brown fox jumps over the lazy dog in the park on a warm summer afternoon. " +
            "Meanwhile, the curious cat watches from the window, wondering if it should go outside to explore the garden. " +
            "Birds are singing loudly in the tall oak trees; the morning sun is warm and bright, casting golden shadows across the freshly mowed lawn. " +
            "\"What a beautiful day!\" said the old farmer, walking slowly toward the river with his wooden cane. " +
            "He carried a basket full of apples, oranges, and fresh bread from the village bakery. " +
            "The children were playing near the stone bridge, laughing, running, and shouting with pure joy as they chased each other through the meadow. " +
            "Suddenly, a loud thunder echoed across the valley: the storm was approaching fast from the western mountains. " +
            "Everyone hurried back home, hoping the rain would not last too long. " +
            "The dark clouds gathered quickly, blocking the sunlight and turning the sky into a deep shade of grey. " +
            "The old woman next door closed her wooden shutters and brought the laundry inside before the first drops began to fall. " +
            "Her garden was filled with roses, tulips, and sunflowers that swayed gently in the growing wind. " +
            "She had spent the entire morning watering the plants and trimming the hedges along the narrow path. " +
            "Down by the harbor, the fishermen secured their boats with thick ropes and heavy anchors. " +
            "The waves were getting stronger, crashing against the wooden dock with increasing force. " +
            "Captain Rodriguez shouted instructions to his crew, making sure every sail was properly folded and every hatch was tightly sealed. " +
            "At the local school, the teacher gathered the students inside the classroom and started reading a story about brave explorers who traveled across the ocean to discover new lands. " +
            "The children listened carefully, their eyes wide with excitement and wonder. " +
            "Some of them drew pictures of ships and treasure maps while others wrote short poems about the sea. " +
            "The hospital on the eastern side of town remained busy throughout the afternoon. " +
            "Doctors and nurses worked together to help patients recover from various illnesses. " +
            "The pharmacy next door provided medicine and supplies to families who needed them most during the difficult season. " +
            "By evening, the storm had passed and the sky cleared up, revealing a magnificent rainbow stretching from one horizon to the other. " +
            "People came outside to admire the colors and breathe the fresh air that smelled of wet earth and pine trees. " +
            "The temperature dropped slightly, bringing a pleasant coolness after the humid afternoon. " +
            "The restaurant on the main street opened its doors for dinner service. " +
            "The chef prepared a special menu featuring grilled salmon, roasted vegetables, mushroom soup, and chocolate cake for dessert. " +
            "Families and friends gathered around tables, sharing stories and laughter while enjoying the delicious food and warm atmosphere. " +
            "As night fell, the streetlights flickered on one by one, illuminating the quiet roads and peaceful neighborhoods. " +
            "The moon appeared behind the remaining clouds, casting a silver glow over the sleeping town. " +
            "Tomorrow would bring another day full of possibilities, adventures, and memories waiting to be created."

        // ~270 words — trimmed to avoid Vosk STT timeout on CI
        private const val ES_TEXT = "El rápido zorro marrón salta sobre el perro perezoso en la plaza grande del pueblo durante una cálida tarde de verano. " +
            "Mientras tanto, el gato curioso observa desde la ventana, preguntándose si debería salir a explorar el jardín. " +
            "Los pájaros cantan con fuerza en los altos robles; el sol de la mañana es cálido y brillante, proyectando sombras doradas sobre el césped recién cortado. " +
            "\"¡Qué día tan hermoso!\" dijo el viejo granjero, caminando despacio hacia el río con su bastón de madera. " +
            "Llevaba una cesta llena de manzanas, naranjas y pan fresco de la panadería del pueblo. " +
            "Los niños jugaban cerca del puente de piedra, riendo, corriendo y gritando de pura alegría mientras se perseguían por la pradera. " +
            "De repente, un fuerte trueno resonó por todo el valle: la tormenta se acercaba rápido desde las montañas del oeste. " +
            "Todos corrieron a casa, esperando que la lluvia no durara demasiado. " +
            "Las nubes oscuras se juntaron rápidamente, bloqueando la luz del sol y convirtiendo el cielo en un profundo tono gris. " +
            "La anciana de al lado cerró sus persianas de madera y recogió la ropa tendida antes de que cayeran las primeras gotas. " +
            "Su jardín estaba lleno de rosas, tulipanes y girasoles que se mecían suavemente con el viento creciente. " +
            "Había pasado toda la mañana regando las plantas y recortando los setos a lo largo del estrecho camino. " +
            "Junto al puerto, los pescadores aseguraron sus barcos con gruesas cuerdas y pesadas anclas. " +
            "Las olas se hacían cada vez más fuertes, golpeando contra el muelle de madera con creciente intensidad. " +
            "El capitán Rodríguez gritaba instrucciones a su tripulación, asegurándose de que cada vela estuviera correctamente plegada y cada escotilla firmemente sellada."

        private val EN_KEYWORDS = listOf(
            // Paragraph 1: park scene
            "fox", "jumps", "lazy", "dog", "park", "summer", "afternoon",
            "cat", "watches", "window", "outside", "explore", "garden",
            "birds", "singing", "oak", "morning", "sun", "warm", "bright", "golden", "shadows", "lawn",
            // Paragraph 2: farmer
            "beautiful", "day", "farmer", "walking", "river", "wooden", "cane",
            "basket", "apples", "oranges", "bread", "village", "bakery",
            "children", "playing", "stone", "bridge", "laughing", "running", "meadow",
            // Paragraph 3: storm
            "thunder", "valley", "storm", "approaching", "western", "mountains",
            "hurried", "home", "rain",
            "dark", "clouds", "sunlight", "sky", "grey",
            // Paragraph 4: old woman
            "woman", "shutters", "laundry", "drops",
            "roses", "tulips", "sunflowers", "wind",
            "watering", "plants", "hedges", "narrow", "path",
            // Paragraph 5: harbor
            "harbor", "fishermen", "boats", "ropes", "anchors",
            "waves", "stronger", "crashing", "dock", "force",
            "captain", "instructions", "crew", "sail", "hatch", "sealed",
            // Paragraph 6: school
            "school", "teacher", "students", "classroom", "reading", "story",
            "explorers", "traveled", "ocean", "discover", "lands",
            "listened", "eyes", "excitement", "wonder",
            "ships", "treasure", "maps", "poems", "sea",
            // Paragraph 7: hospital
            "hospital", "eastern", "town", "busy", "afternoon",
            "doctors", "nurses", "patients", "recover", "illnesses",
            "pharmacy", "medicine", "supplies", "families", "season",
            // Paragraph 8: rainbow
            "evening", "passed", "cleared", "rainbow", "horizon",
            "people", "admire", "colors", "fresh", "air", "earth", "pine",
            "temperature", "dropped", "coolness", "humid",
            // Paragraph 9: restaurant
            "restaurant", "street", "doors", "dinner",
            "chef", "menu", "salmon", "vegetables", "mushroom", "soup", "chocolate", "cake", "dessert",
            "friends", "tables", "stories", "laughter", "food", "atmosphere",
            // Paragraph 10: night
            "night", "streetlights", "flickered", "quiet", "roads", "peaceful", "neighborhoods",
            "moon", "clouds", "silver", "glow", "sleeping",
            "tomorrow", "possibilities", "adventures", "memories"
        )
        private val ES_KEYWORDS = listOf(
            // Párrafo 1: escena del parque
            "marrón", "salta", "perro", "perezoso", "plaza", "pueblo", "verano",
            "curioso", "ventana", "mientras", "explorar", "jardín",
            "cantan", "fuerza", "robles", "sol", "mañana", "cálido", "brillante", "sombras", "doradas", "césped",
            // Párrafo 2: granjero
            "hermoso", "viejo", "granjero", "caminando", "despacio", "río", "bastón", "madera",
            "cesta", "manzanas", "naranjas", "pan", "fresco", "panadería",
            "niños", "jugaban", "puente", "piedra", "riendo", "corriendo", "pradera",
            // Párrafo 3: tormenta
            "trueno", "valle", "tormenta", "acercaba", "montañas", "oeste",
            "corrieron", "casa", "lluvia", "demasiado",
            "nubes", "oscuras", "cielo", "gris",
            // Párrafo 4: anciana
            "anciana", "persianas", "ropa", "gotas",
            "rosas", "tulipanes", "girasoles", "viento",
            "regando", "plantas", "setos", "estrecho", "camino",
            // Párrafo 5: puerto
            "puerto", "pescadores", "barcos", "cuerdas", "anclas",
            "olas", "fuertes", "golpeando", "muelle", "intensidad",
            "capitán", "instrucciones", "tripulación", "vela", "escotilla", "sellada"
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
