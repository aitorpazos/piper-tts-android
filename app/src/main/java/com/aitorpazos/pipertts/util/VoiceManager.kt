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
import android.util.Log
import com.aitorpazos.pipertts.model.PiperVoiceConfig
import java.io.File
import java.util.Locale

/**
 * Manages Piper voice models — loading from assets or external storage.
 *
 * Voice models consist of:
 * - A .onnx model file
 * - A .onnx.json config file
 *
 * Default voice is bundled in assets/voices/
 * Additional voices can be downloaded to app's files directory.
 */
class VoiceManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceManager"
        private const val VOICES_ASSET_DIR = "voices"
        private const val VOICES_EXTERNAL_DIR = "voices"
    }

    data class VoiceData(
        val config: PiperVoiceConfig,
        val modelBytes: ByteArray,
        val locale: Locale,
        val name: String
    )

    data class VoiceInfo(
        val name: String,
        val locale: Locale,
        val quality: String,
        val isAsset: Boolean,
        val modelPath: String,
        val configPath: String
    )

    /**
     * List all available voices (from assets and external storage).
     */
    fun listVoices(): List<VoiceInfo> {
        val voices = mutableListOf<VoiceInfo>()

        // Check assets (may be empty if no voices are bundled)
        try {
            val assetFiles = context.assets.list(VOICES_ASSET_DIR)
            if (assetFiles != null && assetFiles.isNotEmpty()) {
                val onnxFiles = assetFiles.filter { it.endsWith(".onnx") && !it.endsWith(".onnx.json") }

                for (onnxFile in onnxFiles) {
                    val configFile = "$onnxFile.json"
                    if (configFile in assetFiles) {
                        try {
                            val configJson = context.assets.open("$VOICES_ASSET_DIR/$configFile")
                                .bufferedReader().use { it.readText() }
                            val config = PiperVoiceConfig.fromJson(configJson)
                            val locale = parseLocaleFromVoiceName(onnxFile, config)
                            voices.add(
                                VoiceInfo(
                                    name = onnxFile.removeSuffix(".onnx"),
                                    locale = locale,
                                    quality = config.audio.quality ?: "medium",
                                    isAsset = true,
                                    modelPath = "$VOICES_ASSET_DIR/$onnxFile",
                                    configPath = "$VOICES_ASSET_DIR/$configFile"
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse voice config: $configFile", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Assets directory may not exist if no voices are bundled — that's OK
            Log.d(TAG, "No bundled voices in assets (expected if voices are download-only)")
        }

        // Check external storage
        val externalDir = File(context.filesDir, VOICES_EXTERNAL_DIR)
        if (externalDir.exists()) {
            val onnxFiles = externalDir.listFiles { f -> f.extension == "onnx" } ?: emptyArray()
            for (onnxFile in onnxFiles) {
                val configFile = File("${onnxFile.absolutePath}.json")
                if (configFile.exists()) {
                    try {
                        val config = PiperVoiceConfig.fromJson(configFile.readText())
                        val locale = parseLocaleFromVoiceName(onnxFile.name, config)
                        voices.add(
                            VoiceInfo(
                                name = onnxFile.nameWithoutExtension,
                                locale = locale,
                                quality = config.audio.quality ?: "medium",
                                isAsset = false,
                                modelPath = onnxFile.absolutePath,
                                configPath = configFile.absolutePath
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse voice config: ${configFile.name}", e)
                    }
                }
            }
        }

        return voices
    }

    /**
     * Check if a voice is available for the given locale.
     */
    fun hasVoiceForLocale(locale: Locale): Boolean {
        return listVoices().any { it.locale.language == locale.language }
    }

    /**
     * Check if a voice is available for the given language code.
     */
    fun hasVoiceForLanguage(lang: String): Boolean {
        val locale = Locale(lang)
        return hasVoiceForLocale(locale)
    }

    /**
     * Load a voice model for the given locale.
     */
    fun loadVoice(locale: Locale): VoiceData? {
        val voices = listVoices()

        // Find best match: exact locale > language match > default (English)
        val voice = voices.find { it.locale.language == locale.language && it.locale.country == locale.country }
            ?: voices.find { it.locale.language == locale.language }
            ?: voices.find { it.locale.language == "en" }
            ?: voices.firstOrNull()
            ?: return null

        return loadVoiceByInfo(voice)
    }

    /**
     * Load a specific voice by name.
     */
    fun loadVoiceByName(name: String): VoiceData? {
        val voice = listVoices().find { it.name == name } ?: return null
        return loadVoiceByInfo(voice)
    }

    private fun loadVoiceByInfo(voice: VoiceInfo): VoiceData? {
        try {
            val configJson: String
            val modelBytes: ByteArray

            if (voice.isAsset) {
                configJson = context.assets.open(voice.configPath)
                    .bufferedReader().use { it.readText() }
                modelBytes = context.assets.open(voice.modelPath)
                    .use { it.readBytes() }
            } else {
                configJson = File(voice.configPath).readText()
                modelBytes = File(voice.modelPath).readBytes()
            }

            val config = PiperVoiceConfig.fromJson(configJson)

            Log.i(TAG, "Loaded voice: ${voice.name} (${voice.locale}, ${modelBytes.size} bytes)")
            return VoiceData(
                config = config,
                modelBytes = modelBytes,
                locale = voice.locale,
                name = voice.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice: ${voice.name}", e)
            return null
        }
    }

    private fun parseLocaleFromVoiceName(filename: String, config: PiperVoiceConfig): Locale {
        // Try to extract locale from espeak voice config
        config.espeak?.voice?.let { espeakVoice ->
            val parts = espeakVoice.split("-")
            if (parts.size >= 2) {
                return Locale(parts[0], parts[1].uppercase())
            }
            if (parts.isNotEmpty()) {
                return Locale(parts[0])
            }
        }

        // Try to extract from filename (e.g., "en_US-lessac-medium")
        val match = Regex("^([a-z]{2})_([A-Z]{2})").find(filename)
        if (match != null) {
            return Locale(match.groupValues[1], match.groupValues[2])
        }

        val langMatch = Regex("^([a-z]{2})[-_]").find(filename)
        if (langMatch != null) {
            return Locale(langMatch.groupValues[1])
        }

        return Locale.US
    }
}
