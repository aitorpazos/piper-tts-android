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

import com.aitorpazos.pipertts.model.AudioConfig
import com.aitorpazos.pipertts.model.EspeakConfig
import com.aitorpazos.pipertts.model.InferenceConfig
import com.aitorpazos.pipertts.model.PiperVoiceConfig
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the PiperVoiceConfig model and JSON parsing.
 */
class PiperVoiceConfigTest {

    private val gson = Gson()

    @Test
    fun `parse minimal config`() {
        val json = """
        {
            "audio": {
                "sample_rate": 22050
            },
            "num_symbols": 256,
            "num_speakers": 1,
            "phoneme_id_map": {
                "_": [0],
                "a": [1],
                "b": [2]
            }
        }
        """.trimIndent()

        val config = gson.fromJson(json, PiperVoiceConfig::class.java)
        assertEquals(22050, config.audio.sampleRate)
        assertEquals(256, config.numSymbols)
        assertEquals(1, config.numSpeakers)
        assertNotNull(config.phonemeIdMap)
        assertEquals(3, config.phonemeIdMap!!.size)
        assertEquals(listOf(0), config.phonemeIdMap!!["_"])
    }

    @Test
    fun `parse full config with espeak and inference`() {
        val json = """
        {
            "audio": {
                "sample_rate": 22050,
                "quality": "low"
            },
            "espeak": {
                "voice": "en-us"
            },
            "inference": {
                "noise_scale": 0.667,
                "length_scale": 1.0,
                "noise_w": 0.8
            },
            "num_symbols": 65,
            "num_speakers": 1,
            "phoneme_id_map": {
                "_": [0],
                "^": [1],
                "$": [2]
            }
        }
        """.trimIndent()

        val config = gson.fromJson(json, PiperVoiceConfig::class.java)
        assertEquals("en-us", config.espeak?.voice)
        assertEquals(0.667f, config.inference?.noiseScale ?: 0f, 0.001f)
        assertEquals(1.0f, config.inference?.lengthScale ?: 0f, 0.001f)
        assertEquals("low", config.audio.quality)
    }

    @Test
    fun `parse multi-speaker config`() {
        val json = """
        {
            "audio": { "sample_rate": 22050 },
            "num_symbols": 65,
            "num_speakers": 3,
            "speaker_id_map": {
                "speaker_a": 0,
                "speaker_b": 1,
                "speaker_c": 2
            },
            "phoneme_id_map": { "_": [0] }
        }
        """.trimIndent()

        val config = gson.fromJson(json, PiperVoiceConfig::class.java)
        assertEquals(3, config.numSpeakers)
        assertNotNull(config.speakerIdMap)
        assertEquals(0, config.speakerIdMap!!["speaker_a"])
        assertEquals(2, config.speakerIdMap!!["speaker_c"])
    }

    @Test
    fun `default values are applied`() {
        val config = PiperVoiceConfig(
            audio = AudioConfig()
        )
        assertEquals(22050, config.audio.sampleRate)
        assertEquals(0, config.numSymbols)
        assertEquals(1, config.numSpeakers)
        assertNull(config.phonemeIdMap)
    }

    @Test
    fun `inference config defaults`() {
        val config = InferenceConfig()
        assertEquals(0.667f, config.noiseScale, 0.001f)
        assertEquals(1.0f, config.lengthScale, 0.001f)
        assertEquals(0.8f, config.noiseW, 0.001f)
    }

    @Test
    fun `serialize and deserialize roundtrip`() {
        val original = PiperVoiceConfig(
            audio = AudioConfig(sampleRate = 16000, quality = "medium"),
            espeak = EspeakConfig(voice = "en-gb"),
            numSymbols = 128,
            numSpeakers = 2,
            phonemeIdMap = mapOf("_" to listOf(0), "a" to listOf(1, 2)),
            inference = InferenceConfig(noiseScale = 0.5f, lengthScale = 1.2f, noiseW = 0.6f)
        )

        val json = gson.toJson(original)
        val restored = gson.fromJson(json, PiperVoiceConfig::class.java)

        assertEquals(original.audio.sampleRate, restored.audio.sampleRate)
        assertEquals(original.audio.quality, restored.audio.quality)
        assertEquals(original.espeak?.voice, restored.espeak?.voice)
        assertEquals(original.numSymbols, restored.numSymbols)
        assertEquals(original.numSpeakers, restored.numSpeakers)
        assertEquals(original.phonemeIdMap, restored.phonemeIdMap)
        assertEquals(original.inference?.noiseScale ?: 0f, restored.inference?.noiseScale ?: 0f, 0.001f)
    }
}
