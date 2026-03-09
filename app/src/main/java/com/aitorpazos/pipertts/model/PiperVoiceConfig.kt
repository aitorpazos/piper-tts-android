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

import com.google.gson.annotations.SerializedName

/**
 * Piper voice configuration model.
 * Maps to the JSON config files that accompany .onnx voice models.
 */
data class PiperVoiceConfig(
    val audio: AudioConfig,
    val espeak: EspeakConfig? = null,
    @SerializedName("num_symbols")
    val numSymbols: Int = 0,
    @SerializedName("num_speakers")
    val numSpeakers: Int = 1,
    @SerializedName("speaker_id_map")
    val speakerIdMap: Map<String, Int>? = null,
    val phoneme_type: String? = null,
    @SerializedName("phoneme_id_map")
    val phonemeIdMap: Map<String, List<Int>>? = null,
    val inference: InferenceConfig? = null
)

data class AudioConfig(
    @SerializedName("sample_rate")
    val sampleRate: Int = 22050,
    val quality: String? = null
)

data class EspeakConfig(
    val voice: String = "en-us"
)

data class InferenceConfig(
    @SerializedName("noise_scale")
    val noiseScale: Float = 0.667f,
    @SerializedName("length_scale")
    val lengthScale: Float = 1.0f,
    @SerializedName("noise_w")
    val noiseW: Float = 0.8f,
    @SerializedName("phoneme_silence")
    val phonemeSilence: Map<String, Float>? = null
)
