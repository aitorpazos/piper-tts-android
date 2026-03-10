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

package com.aitorpazos.pipertts.model

/**
 * Piper voice configuration model.
 * Maps to the JSON config files that accompany .onnx voice models.
 *
 * Parsed manually with org.json to avoid Gson/R8 TypeToken issues.
 */
data class PiperVoiceConfig(
    val audio: AudioConfig,
    val espeak: EspeakConfig? = null,
    val numSymbols: Int = 0,
    val numSpeakers: Int = 1,
    val speakerIdMap: Map<String, Int>? = null,
    val phonemeType: String? = null,
    val phonemeIdMap: Map<String, List<Int>>? = null,
    val inference: InferenceConfig? = null
) {
    companion object {
        /**
         * Parse a PiperVoiceConfig from a JSON string using org.json.
         * This avoids Gson TypeToken issues under R8/ProGuard minification.
         */
        fun fromJson(json: String): PiperVoiceConfig {
            val obj = org.json.JSONObject(json)

            val audioObj = obj.getJSONObject("audio")
            val audio = AudioConfig(
                sampleRate = audioObj.optInt("sample_rate", 22050),
                quality = if (audioObj.has("quality")) audioObj.getString("quality") else null
            )

            val espeak = if (obj.has("espeak")) {
                val e = obj.getJSONObject("espeak")
                EspeakConfig(voice = e.optString("voice", "en-us"))
            } else null

            val numSymbols = obj.optInt("num_symbols", 0)
            val numSpeakers = obj.optInt("num_speakers", 1)

            val speakerIdMap = if (obj.has("speaker_id_map")) {
                val m = obj.getJSONObject("speaker_id_map")
                val map = mutableMapOf<String, Int>()
                val keys = m.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = m.getInt(k)
                }
                map
            } else null

            val phonemeType = if (obj.has("phoneme_type")) obj.getString("phoneme_type") else null

            val phonemeIdMap = if (obj.has("phoneme_id_map")) {
                val m = obj.getJSONObject("phoneme_id_map")
                val map = mutableMapOf<String, List<Int>>()
                val keys = m.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = m.getJSONArray(k)
                    val list = mutableListOf<Int>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getInt(i))
                    }
                    map[k] = list
                }
                map
            } else null

            val inference = if (obj.has("inference")) {
                val inf = obj.getJSONObject("inference")
                val phonemeSilence = if (inf.has("phoneme_silence")) {
                    val ps = inf.getJSONObject("phoneme_silence")
                    val psMap = mutableMapOf<String, Float>()
                    val psKeys = ps.keys()
                    while (psKeys.hasNext()) {
                        val k = psKeys.next()
                        psMap[k] = ps.getDouble(k).toFloat()
                    }
                    psMap
                } else null

                InferenceConfig(
                    noiseScale = inf.optDouble("noise_scale", 0.667).toFloat(),
                    lengthScale = inf.optDouble("length_scale", 1.0).toFloat(),
                    noiseW = inf.optDouble("noise_w", 0.8).toFloat(),
                    phonemeSilence = phonemeSilence
                )
            } else null

            return PiperVoiceConfig(
                audio = audio,
                espeak = espeak,
                numSymbols = numSymbols,
                numSpeakers = numSpeakers,
                speakerIdMap = speakerIdMap,
                phonemeType = phonemeType,
                phonemeIdMap = phonemeIdMap,
                inference = inference
            )
        }
    }
}

data class AudioConfig(
    val sampleRate: Int = 22050,
    val quality: String? = null
)

data class EspeakConfig(
    val voice: String = "en-us"
)

data class InferenceConfig(
    val noiseScale: Float = 0.667f,
    val lengthScale: Float = 1.0f,
    val noiseW: Float = 0.8f,
    val phonemeSilence: Map<String, Float>? = null
)
