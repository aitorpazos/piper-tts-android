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
