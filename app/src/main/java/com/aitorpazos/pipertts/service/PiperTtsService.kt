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
import com.aitorpazos.pipertts.engine.EspeakNative
import com.aitorpazos.pipertts.engine.PiperEngine
import com.aitorpazos.pipertts.util.VoiceManager
import com.aitorpazos.pipertts.util.VoicePreferences
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
    private lateinit var voicePreferences: VoicePreferences

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PiperTtsService created")
        try {
            voiceManager = VoiceManager(this)
            voicePreferences = VoicePreferences(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing VoiceManager/VoicePreferences", e)
            // Initialize with fallbacks — service must not crash in onCreate
            // or Android will blacklist it from TTS settings
            voiceManager = VoiceManager(this)
            voicePreferences = VoicePreferences(this)
        }

        // NOTE: We do NOT pre-load the voice model here.
        // Loading a 60MB+ ONNX model on the main thread would cause an ANR,
        // and Android would kill the service and mark it as broken.
        // Instead, the voice is loaded lazily on first synthesis (which runs
        // on a binder thread, not the main thread).
        Log.i(TAG, "Service ready — voice will be loaded on first synthesis request")

        // Initialize eSpeak-ng for IPA phonemization (required by all standard Piper voices).
        // This extracts ~25MB of data files from assets on first run and initializes the
        // native library. Subsequent starts are fast (version check only).
        val espeakOk = EspeakNative.initialize(this)
        Log.i(TAG, "eSpeak-ng initialized: $espeakOk")
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

        // Always advertise at least the device's default locale and English
        // so the engine is selectable regardless of which voices are installed.
        // Android uses onGetVoices to determine if the engine supports anything.
        if (voices.isEmpty()) {
            val defaultLocale = Locale.getDefault()
            voices.add(
                Voice(
                    "${defaultLocale.language}-${defaultLocale.country.lowercase().ifEmpty { "default" }}-default",
                    defaultLocale,
                    Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL,
                    true,  // requiresNetworkConnection = true signals voice needs download
                    mutableSetOf("piperVoice", "notInstalled")
                )
            )
            // Also add English if device locale is not English
            if (defaultLocale.language != "en") {
                voices.add(
                    Voice(
                        "en-us-default",
                        Locale.US,
                        Voice.QUALITY_NORMAL,
                        Voice.LATENCY_NORMAL,
                        true,
                        mutableSetOf("piperVoice", "notInstalled")
                    )
                )
            }
        }

        Log.i(TAG, "onGetVoices: returning ${voices.size} voices")
        return voices
    }

    /**
     * Called when the TTS framework wants to use a specific voice by name.
     *
     * CRITICAL: Must return SUCCESS for placeholder voice names (e.g. "en-us-default")
     * even when no actual voice model is installed. Returning ERROR causes Android to
     * consider the engine broken and remove it from TTS settings.
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

        // If voice not found, it may be a placeholder name (e.g. "en-us-default").
        // Return SUCCESS anyway so Android doesn't mark the engine as broken.
        // Actual synthesis will handle the missing engine gracefully.
        Log.i(TAG, "Voice '$voiceName' not found on disk, returning SUCCESS for engine visibility")
        currentVoiceName = voiceName
        return TextToSpeech.SUCCESS
    }

    /**
     * Check if a language is available.
     *
     * Android may pass ISO 639-2/T 3-letter codes (e.g. "eng") or
     * ISO 639-1 2-letter codes (e.g. "en"). We normalise to 2-letter.
     */
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED

        val normLang = normaliseLanguage(lang)
        val normCountry = normaliseCountry(country ?: "")

        val voices = try {
            voiceManager.listVoices()
        } catch (e: Exception) {
            Log.w(TAG, "Error listing voices in onIsLanguageAvailable", e)
            emptyList()
        }

        // If no voices installed yet, still claim the language is available
        // so the engine remains selectable in Android TTS settings.
        // Android probes this method for the device's default locale when
        // deciding whether to show the engine. If we return LANG_NOT_SUPPORTED,
        // some Android versions/OEMs hide the engine entirely.
        // Actual synthesis will prompt user to download a voice.
        if (voices.isEmpty()) {
            return TextToSpeech.LANG_AVAILABLE
        }

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

    /**
     * Return a default voice name for a given locale.
     *
     * NOTE: TextToSpeechService does NOT have an overridable onGetDefaultVoiceNameForLocale.
     * The default voice selection is done through onGetVoices() — Android picks the first
     * matching voice. This helper method is used internally only.
     */
    private fun getDefaultVoiceNameForLocale(lang: String, country: String): String {
        val normLang = normaliseLanguage(lang)
        val normCountry = normaliseCountry(country)

        try {
            val voices = voiceManager.listVoices()
            val match = voices.find {
                it.locale.language == normLang &&
                (normCountry.isEmpty() || it.locale.country.equals(normCountry, ignoreCase = true))
            } ?: voices.find { it.locale.language == normLang }

            if (match != null) return match.name
        } catch (e: Exception) {
            Log.w(TAG, "Error finding default voice for $lang-$country", e)
        }

        return if (normCountry.isNotEmpty()) "$normLang-${normCountry.lowercase()}-default" else "$normLang-default"
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }

        val normLang = normaliseLanguage(lang ?: "en")
        val normCountry = normaliseCountry(country ?: "")
        currentLocale = Locale(normLang, normCountry, variant ?: "")

        // CRITICAL: Do NOT load the actual ONNX model here.
        // Android calls onLoadLanguage during initial service probing (binding).
        // Loading a 60MB+ model at this point blocks the binder thread and can
        // cause Android to consider the engine unresponsive and hide it.
        // The actual model will be loaded lazily in onSynthesizeText on first use.
        Log.i(TAG, "onLoadLanguage($lang/$country/$variant) → reporting availability=$availability (lazy load)")
        return availability
    }

    private fun loadVoiceForLocale(locale: Locale): Int {
        try {
            val voiceData = voiceManager.loadVoice(locale)
            if (voiceData == null) {
                Log.w(TAG, "No voice found for locale: $locale")
                return TextToSpeech.LANG_NOT_SUPPORTED
            }

            // Only close existing engine after we confirmed we have a new voice
            engine?.close()
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
        val text = request.charSequenceText?.toString() ?: ""
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
            Log.i(TAG, "No engine loaded, loading active voice")
            try {
                val voiceData = voiceManager.loadActiveVoice(voicePreferences)
                if (voiceData != null) {
                    engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                    currentLocale = voiceData.locale
                    currentVoiceName = voiceData.name
                    Log.i(TAG, "Lazily loaded active voice: ${voiceData.name}")
                } else {
                    // No voice installed — return silence
                    Log.w(TAG, "No voice models installed yet, returning silence")
                    callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback.done()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load voice, returning silence", e)
                callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
                return
            }
        }

        try {
            val activeEngine = engine ?: run {
                Log.w(TAG, "Engine still null after load attempt, returning silence")
                callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
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
            // CRITICAL: Never call callback.error() — it causes Android to mark
            // the engine as broken and hide it from TTS settings permanently.
            // Return silence instead so the engine stays functional.
            try {
                callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
            } catch (cbErr: Exception) {
                Log.e(TAG, "Callback already started, calling done()", cbErr)
                try { callback.done() } catch (_: Exception) {}
            }
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
