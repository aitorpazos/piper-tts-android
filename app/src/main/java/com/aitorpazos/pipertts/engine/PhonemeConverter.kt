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

import android.util.Log

/**
 * Text-to-phoneme-ID converter for Piper TTS.
 *
 * Supports two modes:
 * 1. **eSpeak mode** (phonemeType = "espeak"): Uses eSpeak-ng to convert text to IPA
 *    phonemes, then maps each IPA symbol to phoneme IDs via the model's phoneme_id_map.
 *    This is the standard mode used by virtually all Piper voices.
 *
 * 2. **Character mode** (fallback): Maps text characters directly to phoneme IDs.
 *    Only used for rare models with phoneme_type != "espeak".
 */
class PhonemeConverter(
    private val phonemeIdMap: Map<String, List<Int>>,
    private val phonemeType: String? = null,
    private val espeakVoice: String? = null
) {
    companion object {
        private const val TAG = "PhonemeConverter"

        // Special tokens used by Piper
        const val PAD = "_"
        const val BOS = "^"
        const val EOS = "$"
    }

    /**
     * Convert input text to a list of phoneme IDs suitable for the ONNX model.
     *
     * For espeak phoneme_type: text → eSpeak-ng → IPA string → phoneme IDs
     * For character phoneme_type: text → character lookup → phoneme IDs
     */
    fun textToPhonemeIds(text: String): LongArray {
        return if (phonemeType == "espeak" && espeakVoice != null) {
            espeakTextToPhonemeIds(text)
        } else {
            characterTextToPhonemeIds(text)
        }
    }

    /**
     * eSpeak-ng based phonemization (standard Piper pipeline).
     *
     * 1. Call eSpeak-ng to convert text to IPA phonemes
     * 2. Map each IPA character/symbol to phoneme IDs using the model's phoneme_id_map
     * 3. Insert padding between phonemes
     */
    private fun espeakTextToPhonemeIds(text: String): LongArray {
        val ipaString = EspeakNative.textToPhonemes(espeakVoice!!, text)

        if (ipaString.isEmpty()) {
            Log.w(TAG, "eSpeak returned empty phonemes for: '${text.take(50)}', falling back to character mode")
            return characterTextToPhonemeIds(text)
        }

        Log.d(TAG, "eSpeak IPA: '${ipaString.take(100)}' for text: '${text.take(50)}'")

        val ids = mutableListOf<Long>()
        val padId = phonemeIdMap[PAD]?.firstOrNull()?.toLong() ?: 0L
        val bosIds = phonemeIdMap[BOS]?.map { it.toLong() }
        val eosIds = phonemeIdMap[EOS]?.map { it.toLong() }

        // BOS
        bosIds?.let { ids.addAll(it) }
        ids.add(padId)

        // Process each IPA character
        // eSpeak-ng may return multi-word output separated by spaces
        // Each character in the IPA string is looked up in phoneme_id_map
        for (char in ipaString) {
            val charStr = char.toString()
            val charIds = phonemeIdMap[charStr]?.map { it.toLong() }
            if (charIds != null) {
                ids.addAll(charIds)
                ids.add(padId)
            }
            // Skip characters not in the phoneme_id_map (e.g. stress marks
            // that may not be in the map for some models)
        }

        // EOS
        eosIds?.let { ids.addAll(it) }
        ids.add(padId)

        return ids.toLongArray()
    }

    /**
     * Character-based phonemization (fallback for non-espeak models).
     *
     * Strategy:
     * 1. Lowercase the text
     * 2. Add BOS token
     * 3. For each character, look up its phoneme IDs and insert padding between them
     * 4. Add EOS token
     */
    private fun characterTextToPhonemeIds(text: String): LongArray {
        val ids = mutableListOf<Long>()
        val padId = phonemeIdMap[PAD]?.firstOrNull()?.toLong() ?: 0L
        val bosIds = phonemeIdMap[BOS]?.map { it.toLong() }
        val eosIds = phonemeIdMap[EOS]?.map { it.toLong() }

        // BOS
        bosIds?.let { ids.addAll(it) }
        ids.add(padId)

        val normalizedText = normalizeText(text)

        for (char in normalizedText) {
            val charStr = char.toString()
            val charIds = phonemeIdMap[charStr]?.map { it.toLong() }
            if (charIds != null) {
                ids.addAll(charIds)
                ids.add(padId)
            }
            // Skip unknown characters silently
        }

        // EOS
        eosIds?.let { ids.addAll(it) }
        ids.add(padId)

        return ids.toLongArray()
    }

    /**
     * Basic text normalization for character-based mode.
     */
    private fun normalizeText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[\\n\\r\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
