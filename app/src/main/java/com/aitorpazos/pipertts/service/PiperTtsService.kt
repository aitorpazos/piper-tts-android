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
import android.speech.tts.Voice
import android.util.Log
import com.aitorpazos.pipertts.engine.PiperEngine
import com.aitorpazos.pipertts.util.VoiceManager
import java.util.Locale

/**
 * Android system TTS engine service powered by Piper.
 *
 * This service registers as a system TTS provider, allowing any app to use
 * Piper TTS for speech synthesis through the standard Android TTS API.
 *
 * Key requirements for Android to accept this as a TTS engine:
 * 1. Declared as a service with BIND_TEXT_TO_SPEECH_SERVICE permission
 * 2. Intent filter for android.intent.action.TTS_SERVICE
 * 3. Meta-data pointing to tts_engine.xml
 * 4. Properly implements onIsLanguageAvailable, onLoadLanguage, onGetLanguage
 * 5. Implements onGetVoices to advertise available voices
 */
class PiperTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "PiperTtsService"

        /**
         * Map ISO 639-2/T (3-letter) codes that Android may pass to ISO 639-1 (2-letter).
         * Android's TTS framework sometimes sends 3-letter codes (e.g., "eng", "spa").
         */
        private val ISO3_TO_ISO1 = buildIso3Map()

        private fun buildIso3Map(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (loc in Locale.getAvailableLocales()) {
                try {
                    val iso3 = loc.isO3Language
                    val iso1 = loc.language
                    if (iso3.isNotEmpty() && iso1.isNotEmpty() && iso3.length == 3 && iso1.length == 2) {
                        map[iso3] = iso1
                    }
                } catch (_: Exception) {
                    // Some locales throw MissingResourceException for getISO3Language
                }
            }
            return map
        }

        /**
         * Normalise a language code to 2-letter ISO 639-1.
         * Accepts "en", "eng", "en-us", "en_US", etc.
         */
        fun normaliseLanguage(lang: String): String {
            val lower = lang.lowercase().replace('-', '_')
            // Already 2-letter
            if (lower.length == 2) return lower
            // 3-letter ISO 639-2/T
            if (lower.length == 3) return ISO3_TO_ISO1[lower] ?: lower
            // "en_us" style
            val parts = lower.split('_')
            if (parts.isNotEmpty()) {
                val first = parts[0]
                if (first.length == 2) return first
                if (first.length == 3) return ISO3_TO_ISO1[first] ?: first
            }
            return lower
        }

        fun normaliseCountry(country: String): String {
            val upper = country.uppercase()
            if (upper.length <= 3) return upper
            return upper
        }
    }

    private var engine: PiperEngine? = null
    private var currentLocale: Locale = Locale.US
    private var currentVoiceName: String? = null
    private lateinit var voiceManager: VoiceManager

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PiperTtsService created")
        voiceManager = VoiceManager(this)

        // Pre-load default voice if available
        try {
            val voices = voiceManager.listVoices()
            if (voices.isNotEmpty()) {
                val defaultVoice = voices.find { it.locale.language == "en" } ?: voices.first()
                loadVoiceForLocale(defaultVoice.locale)
                Log.i(TAG, "Pre-loaded default voice: ${defaultVoice.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pre-load default voice", e)
        }
    }

    /**
     * Return the list of voices available from this TTS engine.
     * Android TTS framework calls this to populate the voice list in Settings.
     */
    override fun onGetVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        try {
            val piperVoices = voiceManager.listVoices()
            for (pv in piperVoices) {
                val features = mutableSetOf<String>()
                features.add("piperVoice")

                val quality = when {
                    pv.quality.contains("high", ignoreCase = true) -> Voice.QUALITY_VERY_HIGH
                    pv.quality.contains("medium", ignoreCase = true) -> Voice.QUALITY_NORMAL
                    pv.quality.contains("low", ignoreCase = true) -> Voice.QUALITY_LOW
                    else -> Voice.QUALITY_NORMAL
                }

                val voice = Voice(
                    pv.name,                              // unique name
                    pv.locale,                            // locale
                    quality,                              // quality
                    Voice.LATENCY_NORMAL,                 // latency
                    false,                                // requiresNetworkConnection
                    features                              // features set
                )
                voices.add(voice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing voices", e)
        }

        // If no voices installed, still advertise English as available
        // (it will prompt the user to download)
        if (voices.isEmpty()) {
            voices.add(
                Voice(
                    "en-us-default",
                    Locale.US,
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL,
                    false,
                    mutableSetOf("piperVoice")
                )
            )
        }

        Log.i(TAG, "onGetVoices: returning ${voices.size} voices")
        return voices
    }

    /**
     * Called when the TTS framework wants to use a specific voice by name.
     */
    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR

        Log.i(TAG, "onLoadVoice: $voiceName")

        try {
            val voiceData = voiceManager.loadVoiceByName(voiceName)
            if (voiceData != null) {
                engine?.close()
                engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                currentLocale = voiceData.locale
                currentVoiceName = voiceName
                return TextToSpeech.SUCCESS
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice: $voiceName", e)
        }

        return TextToSpeech.ERROR
    }

    /**
     * Check if a language is available.
     *
     * Android may pass ISO 639-2/T 3-letter codes (e.g. "eng") or
     * ISO 639-1 2-letter codes (e.g. "en"). We normalise to 2-letter.
     */
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED

        val normLang = normaliseLanguage(lang)
        val normCountry = normaliseCountry(country ?: "")

        val voices = voiceManager.listVoices()

        val exactMatch = voices.any {
            it.locale.language == normLang &&
            (normCountry.isEmpty() || it.locale.country.equals(normCountry, ignoreCase = true))
        }
        val langMatch = voices.any { it.locale.language == normLang }

        Log.d(TAG, "onIsLanguageAvailable($lang/$country/$variant) → norm=$normLang/$normCountry exact=$exactMatch lang=$langMatch voices=${voices.size}")

        return when {
            exactMatch -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            langMatch -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(
            currentLocale.language,
            currentLocale.country,
            ""
        )
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }

        val normLang = normaliseLanguage(lang ?: "en")
        val normCountry = normaliseCountry(country ?: "")
        val locale = Locale(normLang, normCountry, variant ?: "")
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
            currentVoiceName = voiceData.name
            Log.i(TAG, "Loaded voice for locale: $locale (${voiceData.name})")
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
            val normLang = normaliseLanguage(requestLang)
            val normCountry = normaliseCountry(requestCountry ?: "")
            val requestLocale = Locale(normLang, normCountry)
            if (requestLocale.language != currentLocale.language) {
                loadVoiceForLocale(requestLocale)
            }
        }

        // Check if a specific voice was requested
        val requestedVoiceName = request.voiceName
        if (requestedVoiceName != null && requestedVoiceName != currentVoiceName) {
            onLoadVoice(requestedVoiceName)
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
