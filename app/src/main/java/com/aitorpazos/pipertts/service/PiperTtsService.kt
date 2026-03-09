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

package com.aitorpazos.pipertts.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.aitorpazos.pipertts.engine.PiperEngine
import com.aitorpazos.pipertts.util.VoiceManager
import java.util.Locale

/**
 * Android system TTS engine service powered by Piper.
 *
 * This service registers as a system TTS provider, allowing any app to use
 * Piper TTS for speech synthesis through the standard Android TTS API.
 */
class PiperTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "PiperTtsService"

        // Supported languages - can be expanded with more voice models
        private val SUPPORTED_LANGUAGES = mapOf(
            "eng" to Locale.US,     // en_US
            "fra" to Locale.FRANCE, // fr_FR
            "deu" to Locale.GERMANY, // de_DE
            "spa" to Locale("es", "ES"),
            "ita" to Locale.ITALY,
            "por" to Locale("pt", "BR"),
            "nld" to Locale("nl", "NL"),
            "pol" to Locale("pl", "PL"),
            "rus" to Locale("ru", "RU"),
            "zho" to Locale.CHINA,
        )
    }

    private var engine: PiperEngine? = null
    private var currentLocale: Locale = Locale.US
    private lateinit var voiceManager: VoiceManager

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PiperTtsService created")
        voiceManager = VoiceManager(this)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED

        val locale = Locale(lang, country ?: "", variant ?: "")

        // Check if we have a voice model for this language
        val hasModel = voiceManager.hasVoiceForLocale(locale)

        return when {
            hasModel && !country.isNullOrEmpty() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            hasModel -> TextToSpeech.LANG_AVAILABLE
            voiceManager.hasVoiceForLanguage(lang) -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(
            currentLocale.isO3Language ?: "eng",
            currentLocale.isO3Country ?: "USA",
            ""
        )
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }

        val locale = Locale(lang ?: "en", country ?: "US", variant ?: "")
        return loadVoiceForLocale(locale)
    }

    private fun loadVoiceForLocale(locale: Locale): Int {
        try {
            // Close existing engine
            engine?.close()

            val voiceData = voiceManager.loadVoice(locale)
            if (voiceData == null) {
                Log.w(TAG, "No voice found for locale: $locale")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }

            engine = PiperEngine(voiceData.config, voiceData.modelBytes)
            currentLocale = locale
            Log.i(TAG, "Loaded voice for locale: $locale")
            return TextToSpeech.LANG_COUNTRY_AVAILABLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice for locale: $locale", e)
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: request.text ?: ""
        if (text.isBlank()) {
            callback.done()
            return
        }

        Log.d(TAG, "Synthesizing: '${text.take(100)}'")

        // Check if we need to load a different language
        val requestLang = request.language
        val requestCountry = request.country
        if (requestLang != null) {
            val requestLocale = Locale(requestLang, requestCountry ?: "")
            if (requestLocale.language != currentLocale.language) {
                loadVoiceForLocale(requestLocale)
            }
        }

        val currentEngine = engine
        if (currentEngine == null) {
            Log.e(TAG, "No engine loaded, attempting to load default")
            if (loadVoiceForLocale(Locale.US) == TextToSpeech.LANG_NOT_SUPPORTED) {
                callback.error()
                return
            }
        }

        try {
            val activeEngine = engine ?: run {
                callback.error()
                return
            }

            // Get speech rate from request (default 100 = 1.0x)
            val speechRate = request.speechRate / 100.0f
            val lengthScale = 1.0f / speechRate.coerceIn(0.25f, 4.0f)

            // Synthesize
            val samples = activeEngine.synthesize(
                text = text,
                lengthScale = lengthScale
            )

            if (samples.isEmpty()) {
                callback.done()
                return
            }

            // Convert to 16-bit PCM
            val pcmData = activeEngine.floatToInt16Pcm(samples)

            // Start audio output
            val sampleRate = activeEngine.sampleRate
            callback.start(
                sampleRate,
                AudioFormat.ENCODING_PCM_16BIT,
                1 // mono
            )

            // Write audio in chunks to allow for interruption
            val chunkSize = 4096
            var offset = 0
            while (offset < pcmData.size) {
                if (callback.hasStarted()) {
                    val remaining = pcmData.size - offset
                    val writeSize = minOf(chunkSize, remaining)
                    callback.audioAvailable(pcmData, offset, writeSize)
                    offset += writeSize
                } else {
                    break
                }
            }

            callback.done()
            Log.d(TAG, "Synthesis complete: ${pcmData.size} bytes")

        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
        }
    }

    override fun onStop() {
        Log.d(TAG, "onStop called")
        // Synthesis is synchronous, so nothing to cancel mid-stream
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
        engine = null
        Log.i(TAG, "PiperTtsService destroyed")
    }
}
