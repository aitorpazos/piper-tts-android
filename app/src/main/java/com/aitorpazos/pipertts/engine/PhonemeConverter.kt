package com.aitorpazos.pipertts.engine

/**
 * Simple text-to-phoneme-ID converter for Piper TTS.
 *
 * Piper models use a phoneme_id_map that maps individual characters (or phoneme symbols)
 * to integer IDs. For "low" phoneme_type models, we map text characters directly.
 * For espeak-based models, you'd need espeak-ng; this implementation covers the
 * character-based approach which works with many Piper models.
 */
class PhonemeConverter(
    private val phonemeIdMap: Map<String, List<Int>>
) {
    companion object {
        // Special tokens used by Piper
        const val PAD = "_"
        const val BOS = "^"
        const val EOS = "$"
    }

    /**
     * Convert input text to a list of phoneme IDs suitable for the ONNX model.
     *
     * Strategy:
     * 1. Lowercase the text
     * 2. Add BOS token
     * 3. For each character, look up its phoneme IDs and insert padding between them
     * 4. Add EOS token
     */
    fun textToPhonemeIds(text: String): LongArray {
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
     * Basic text normalization.
     */
    private fun normalizeText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[\\n\\r\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
