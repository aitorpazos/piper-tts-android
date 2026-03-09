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

package com.aitorpazos.pipertts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.aitorpazos.pipertts.model.PiperVoiceConfig
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Core Piper TTS inference engine using ONNX Runtime.
 *
 * Loads a Piper .onnx voice model and its config, converts text to phoneme IDs,
 * runs inference, and returns raw PCM audio samples.
 */
class PiperEngine(
    private val config: PiperVoiceConfig,
    modelBytes: ByteArray
) {
    companion object {
        private const val TAG = "PiperEngine"
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val phonemeConverter: PhonemeConverter

    val sampleRate: Int get() = config.audio.sampleRate

    init {
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = ortEnv.createSession(modelBytes, sessionOptions)
        phonemeConverter = PhonemeConverter(config.phonemeIdMap ?: emptyMap())
        Log.i(TAG, "PiperEngine initialized. Sample rate: ${config.audio.sampleRate}")
    }

    /**
     * Synthesize speech from text.
     * @param text Input text to synthesize
     * @param speakerId Speaker ID for multi-speaker models (default 0)
     * @param lengthScale Speed control (< 1.0 = faster, > 1.0 = slower)
     * @param noiseScale Variation in speech (default from config)
     * @param noiseW Phoneme width noise (default from config)
     * @return PCM float audio samples at the model's sample rate
     */
    fun synthesize(
        text: String,
        speakerId: Int = 0,
        lengthScale: Float = config.inference?.lengthScale ?: 1.0f,
        noiseScale: Float = config.inference?.noiseScale ?: 0.667f,
        noiseW: Float = config.inference?.noiseW ?: 0.8f
    ): FloatArray {
        if (text.isBlank()) return FloatArray(0)

        val phonemeIds = phonemeConverter.textToPhonemeIds(text)
        if (phonemeIds.isEmpty()) return FloatArray(0)

        Log.d(TAG, "Synthesizing ${phonemeIds.size} phoneme IDs for text: '${text.take(50)}...'")

        return runInference(phonemeIds, speakerId, lengthScale, noiseScale, noiseW)
    }

    private fun runInference(
        phonemeIds: LongArray,
        speakerId: Int,
        lengthScale: Float,
        noiseScale: Float,
        noiseW: Float
    ): FloatArray {
        val inputLength = phonemeIds.size.toLong()

        // Input tensors
        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(phonemeIds),
            longArrayOf(1, inputLength)
        )
        val inputLengthsTensor = OnnxTensor.createTensor(
            ortEnv,
            LongBuffer.wrap(longArrayOf(inputLength)),
            longArrayOf(1)
        )
        val scalesTensor = OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(floatArrayOf(noiseScale, lengthScale, noiseW)),
            longArrayOf(3)
        )

        val inputs = mutableMapOf<String, OnnxTensor>(
            "input" to inputTensor,
            "input_lengths" to inputLengthsTensor,
            "scales" to scalesTensor
        )

        // Add speaker ID for multi-speaker models
        if (config.numSpeakers > 1) {
            val sidTensor = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(longArrayOf(speakerId.toLong())),
                longArrayOf(1)
            )
            inputs["sid"] = sidTensor
        }

        try {
            val result = session.run(inputs)
            val outputTensor = result[0] as OnnxTensor

            // Output shape is [1, 1, num_samples]
            val rawOutput = outputTensor.floatBuffer
            val samples = FloatArray(rawOutput.remaining())
            rawOutput.get(samples)

            Log.d(TAG, "Generated ${samples.size} audio samples")
            return samples
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * Convert float PCM samples to 16-bit PCM byte array for AudioTrack playback.
     */
    fun floatToInt16Pcm(samples: FloatArray): ByteArray {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val intVal = (clamped * 32767).toInt().toShort()
            pcm[i * 2] = (intVal.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (intVal.toInt() shr 8 and 0xFF).toByte()
        }
        return pcm
    }

    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX session", e)
        }
    }
}
