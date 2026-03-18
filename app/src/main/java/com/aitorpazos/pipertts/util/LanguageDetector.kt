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
import android.os.Build
import android.util.Log
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import java.util.Locale

/**
 * Language detection utility using Android's built-in TextClassifier (API 29+).
 *
 * Used as a **last-resort fallback** when no language is specified by the calling
 * app via setLanguage(), setVoice(), or custom params.  This lets Piper TTS
 * auto-detect the language of the text being spoken and pick the correct voice.
 *
 * Limitations:
 * - Only available on Android 10 (API 29) and above
 * - Accuracy is best on full sentences; short text (names, single words) is unreliable
 * - Returns null if confidence is below the threshold or if the API is unavailable
 */
object LanguageDetector {

    private const val TAG = "LanguageDetector"

    /**
     * Minimum confidence score (0.0–1.0) to accept a detected language.
     * Below this threshold we return null (unknown), letting the caller
     * fall back to the current/default voice instead of guessing wrong.
     */
    private const val MIN_CONFIDENCE = 0.5f

    /**
     * Minimum text length to attempt detection.  Very short strings
     * (contact names, single words) produce unreliable results.
     */
    private const val MIN_TEXT_LENGTH = 8

    /**
     * Detect the language of [text] using Android's TextClassifier.
     *
     * @param context Android context (needed to obtain TextClassificationManager)
     * @param text    The text to analyse
     * @return A [Locale] with at least the language set, or null if detection
     *         is unavailable, the text is too short, or confidence is too low.
     */
    fun detectLanguage(context: Context, text: String): Locale? {
        // Guard: API 29+ only
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "TextClassifier requires API 29+, current=${Build.VERSION.SDK_INT}")
            return null
        }

        // Guard: text too short for reliable detection
        val trimmed = text.trim()
        if (trimmed.length < MIN_TEXT_LENGTH) {
            Log.d(TAG, "Text too short for language detection (${trimmed.length} chars)")
            return null
        }

        return try {
            detectLanguageApi29(context, trimmed)
        } catch (e: Exception) {
            Log.w(TAG, "Language detection failed", e)
            null
        }
    }

    /**
     * Actual detection logic, isolated in a separate method so the API 29
     * class references are only loaded when we know we're on API 29+.
     */
    @Suppress("NewApi")
    private fun detectLanguageApi29(context: Context, text: String): Locale? {
        val tcm = context.getSystemService(Context.TEXT_CLASSIFICATION_SERVICE)
            as? TextClassificationManager
        if (tcm == null) {
            Log.w(TAG, "TextClassificationManager not available")
            return null
        }

        val classifier = tcm.textClassifier

        val request = TextLanguage.Request.Builder(text).build()
        val result = classifier.detectLanguage(request)

        if (result.localeHypothesisCount == 0) {
            Log.d(TAG, "No language hypotheses returned")
            return null
        }

        // Get the top hypothesis
        val topLocale = result.getLocale(0)
        val topConfidence = result.getConfidenceScore(topLocale)

        Log.d(TAG, "Top detection: ${topLocale.toLanguageTag()} " +
            "(confidence=${"%.2f".format(topConfidence)}) " +
            "from ${result.localeHypothesisCount} hypotheses")

        // Log additional hypotheses for debugging
        if (result.localeHypothesisCount > 1) {
            for (i in 1 until minOf(result.localeHypothesisCount, 3)) {
                val loc = result.getLocale(i)
                val conf = result.getConfidenceScore(loc)
                Log.d(TAG, "  #${i + 1}: ${loc.toLanguageTag()} (confidence=${"%.2f".format(conf)})")
            }
        }

        if (topConfidence < MIN_CONFIDENCE) {
            Log.d(TAG, "Confidence ${"%.2f".format(topConfidence)} below threshold $MIN_CONFIDENCE, ignoring")
            return null
        }

        // Convert ULocale to java.util.Locale
        val locale = topLocale.toLocale()
        Log.i(TAG, "Detected language: ${locale.language}/${locale.country} " +
            "(confidence=${"%.2f".format(topConfidence)})")
        return locale
    }
}
