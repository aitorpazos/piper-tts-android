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
import com.aitorpazos.pipertts.util.LanguageDetector
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

        // Custom params keys for direct language override.
        // Apps can pass these in the speak() params Bundle to bypass the
        // Android TTS framework's unreliable voice/language state management.
        // This provides a guaranteed-reliable way to specify the desired language.
        const val EXTRA_PIPER_LANGUAGE = "piper_language"    // 2-letter ISO 639-1 (e.g. "en")
        const val EXTRA_PIPER_COUNTRY  = "piper_country"     // 2-letter ISO 3166-1 (e.g. "GB")
        const val EXTRA_PIPER_VOICE    = "piper_voice_name"  // exact voice name (e.g. "en_GB-alba-medium")

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

        /**
         * Map ISO 3166-1 alpha-3 (3-letter) country codes to alpha-2 (2-letter).
         * Android's TTS framework sends 3-letter codes (e.g., "GBR", "ESP", "USA")
         * but Piper voice locales use 2-letter codes (e.g., "GB", "ES", "US").
         */
        private val ISO3_COUNTRY_TO_ISO2 = buildIso3CountryMap()

        private fun buildIso3CountryMap(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (loc in Locale.getAvailableLocales()) {
                try {
                    val iso3 = loc.isO3Country
                    val iso2 = loc.country
                    if (iso3.isNotEmpty() && iso2.isNotEmpty() && iso3.length == 3 && iso2.length == 2) {
                        map[iso3.uppercase()] = iso2.uppercase()
                    }
                } catch (_: Exception) {
                    // Some locales throw MissingResourceException
                }
            }
            return map
        }

        /**
         * Normalise a country code to 2-letter ISO 3166-1 alpha-2.
         * Accepts "US", "USA", "GBR", "gb", etc.
         */
        fun normaliseCountry(country: String): String {
            val upper = country.uppercase().trim()
            if (upper.isEmpty()) return upper
            // Already 2-letter
            if (upper.length == 2) return upper
            // 3-letter → convert to 2-letter
            if (upper.length == 3) return ISO3_COUNTRY_TO_ISO2[upper] ?: upper
            return upper
        }

        /**
         * Extract a 2-letter language and optional 2-letter country from a Piper
         * voice name like "es_ES-davefx-medium" or "en_GB-alba-medium".
         *
         * Returns a Pair(language, country) or null if the name doesn't match
         * the expected format.
         */
        fun extractLocaleFromVoiceName(voiceName: String): Pair<String, String>? {
            // Pattern: xx_XX-name-quality (e.g., es_ES-davefx-medium)
            val match = Regex("^([a-z]{2})_([A-Z]{2})").find(voiceName)
            if (match != null) {
                return Pair(match.groupValues[1], match.groupValues[2])
            }
            // Pattern: xx-name (e.g., en-default)
            val langMatch = Regex("^([a-z]{2})[-_]").find(voiceName)
            if (langMatch != null) {
                return Pair(langMatch.groupValues[1], "")
            }
            return null
        }
    }

    private var engine: PiperEngine? = null
    private var currentLocale: Locale = Locale.US
    private var currentVoiceName: String? = null
    /**
     * Tracks the locale that was actually loaded into the engine (model on disk).
     * This may differ from [currentLocale] which is updated optimistically by
     * onLoadLanguage (without loading the model) to satisfy Android framework probing.
     */
    private var loadedLocale: Locale? = null
    /** Tracks the voice name that was actually loaded into the engine. */
    private var loadedVoiceName: String? = null
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

        Log.i(TAG, "onLoadVoice: $voiceName (current loaded=$loadedVoiceName)")

        try {
            val voiceData = voiceManager.loadVoiceByName(voiceName)
            if (voiceData != null) {
                engine?.close()
                engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                currentLocale = voiceData.locale
                currentVoiceName = voiceName
                loadedLocale = voiceData.locale
                loadedVoiceName = voiceName
                Log.i(TAG, "onLoadVoice: loaded $voiceName (locale=${voiceData.locale})")
                return TextToSpeech.SUCCESS
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice: $voiceName", e)
        }

        // If voice not found, it may be a placeholder name (e.g. "en-us-default").
        // Return SUCCESS anyway so Android doesn't mark the engine as broken.
        // Actual synthesis will handle the missing engine gracefully.
        // NOTE: Do NOT set currentVoiceName/loadedVoiceName here — if we didn't
        // actually load the voice, keeping the old name ensures the next request
        // with this voice name will retry loading instead of being skipped.
        Log.i(TAG, "Voice '$voiceName' not found on disk, returning SUCCESS for engine visibility")
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
     * The Android TTS framework calls this during client initialization to pick
     * a default voice. The base class implementation iterates onGetVoices() and
     * matches on 3-letter ISO codes. We override to use our normalisation logic
     * so the correct voice is returned regardless of 2-letter vs 3-letter codes.
     */
    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String {
        val normLang = normaliseLanguage(lang)
        val normCountry = normaliseCountry(country)

        try {
            val voices = voiceManager.listVoices()
            // Exact match (language + country)
            val exact = voices.find {
                it.locale.language == normLang &&
                normCountry.isNotEmpty() &&
                it.locale.country.equals(normCountry, ignoreCase = true)
            }
            if (exact != null) {
                Log.d(TAG, "onGetDefaultVoiceNameFor($lang/$country/$variant) → ${exact.name}")
                return exact.name
            }

            // Language-only match
            val langMatch = voices.find { it.locale.language == normLang }
            if (langMatch != null) {
                Log.d(TAG, "onGetDefaultVoiceNameFor($lang/$country/$variant) → ${langMatch.name} (lang-only)")
                return langMatch.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in onGetDefaultVoiceNameFor", e)
        }

        // Fallback to a synthetic name — the engine will handle it gracefully
        val fallback = if (normCountry.isNotEmpty()) "$normLang-${normCountry.lowercase()}-default" else "$normLang-default"
        Log.d(TAG, "onGetDefaultVoiceNameFor($lang/$country/$variant) → $fallback (fallback)")
        return fallback
    }

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
            loadedLocale = voiceData.locale
            loadedVoiceName = voiceData.name
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

        // --- Read the params Bundle for custom direct-override keys ---
        // SynthesisRequest.getParams() is a public API.
        // The Bundle contains all keys the client passed to speak()/synthesizeToFile(),
        // merged with the framework's internal params (KEY_PARAM_LANGUAGE, etc.).
        //
        // Custom keys (piper_language, piper_country, piper_voice_name) let apps
        // bypass the Android TTS framework's unreliable voice/language state
        // management and specify the desired voice directly.
        var piperLang: String? = null
        var piperCountry: String? = null
        var piperVoice: String? = null
        var bundleLang: String? = null
        var bundleCountry: String? = null
        var bundleVoiceName: String? = null
        val paramsBundle: android.os.Bundle? = try {
            request.getParams()
        } catch (e: Exception) {
            Log.e(TAG, "request.getParams() threw exception", e)
            null
        }
        if (paramsBundle != null) {
            // Log ALL keys and their types for debugging
            try {
                val keys = paramsBundle.keySet()
                Log.i(TAG, "Params Bundle has ${keys?.size ?: 0} keys: ${keys?.joinToString { k ->
                    val v = paramsBundle.get(k)
                    "$k=${v}(${v?.javaClass?.simpleName})"
                }}")
            } catch (e: Exception) {
                Log.w(TAG, "Error logging bundle keys", e)
            }

            piperLang = paramsBundle.getString(EXTRA_PIPER_LANGUAGE)
            piperCountry = paramsBundle.getString(EXTRA_PIPER_COUNTRY) ?: ""
            piperVoice = paramsBundle.getString(EXTRA_PIPER_VOICE)

            // Also read the framework's own language/voice keys from the Bundle.
            bundleLang = paramsBundle.getString("language")
            bundleCountry = paramsBundle.getString("country")
            bundleVoiceName = paramsBundle.getString("voiceName")
        } else {
            Log.w(TAG, "Params Bundle is null!")
        }

        Log.i(TAG, "onSynthesizeText: '${text.take(80)}' | " +
            "request[voice=${request.voiceName} lang=${request.language} country=${request.country}] | " +
            "piper[lang=$piperLang country=$piperCountry voice=$piperVoice] | " +
            "bundle[lang=$bundleLang country=$bundleCountry voice=$bundleVoiceName] | " +
            "loaded[voice=$loadedVoiceName locale=$loadedLocale] | " +
            "current[locale=$currentLocale voice=$currentVoiceName]")

        // --- PRIORITY 1: Direct override params (piper_language / piper_voice_name) ---
        // This is the most reliable path — completely bypasses framework state.
        // NOTE: Custom Bundle keys may be stripped by the Android TTS framework
        // during IPC on some Android versions. If piperLang is null, the keys
        // didn't survive — fall through to the standard path.
        if (piperLang != null || piperVoice != null) {
            Log.i(TAG, "Using DIRECT PARAMS path: piperLang=$piperLang piperCountry=$piperCountry piperVoice=$piperVoice")
            val resolved = resolveVoiceFromDirectParams(piperLang, piperCountry ?: "", piperVoice)
            if (resolved) {
                synthesizeWithCurrentEngine(text, request, callback)
                return
            }
            Log.w(TAG, "Direct params resolution failed, falling back to standard path")
        }

        var needsReload = engine == null

        // --- Determine the effective language for this request ---
        //
        // The Android TTS framework's language/voice state is complex and unreliable.
        // After setLanguage() + setVoice(), the framework may have:
        //   - request.voiceName = correct Piper voice (e.g. "es_ES-davefx-medium")
        //   - request.language = WRONG (system default, if setLanguage() failed internally)
        //
        // Or after only setLanguage() (no setVoice()):
        //   - request.voiceName = system default voice (WRONG)
        //   - request.language = correct (from setLanguage())
        //
        // Strategy: collect language from ALL sources, then pick the best.
        // If voice name and request.language agree → high confidence.
        // If they disagree → trust voice name (more specific, from setVoice()).

        // Collect language from each source
        var voiceNameLang: String? = null
        var voiceNameCountry: String? = ""
        var voiceNameSource: String? = null

        // From request.voiceName
        if (request.voiceName != null && request.voiceName.isNotEmpty()) {
            val extracted = extractLocaleFromVoiceName(request.voiceName)
            if (extracted != null) {
                voiceNameLang = extracted.first
                voiceNameCountry = extracted.second
                voiceNameSource = "request.voiceName='${request.voiceName}'"
            }
        }
        // From bundle voiceName (if request.voiceName didn't yield a result)
        if (voiceNameLang == null && bundleVoiceName != null && bundleVoiceName.isNotEmpty()) {
            val extracted = extractLocaleFromVoiceName(bundleVoiceName)
            if (extracted != null) {
                voiceNameLang = extracted.first
                voiceNameCountry = extracted.second
                voiceNameSource = "bundle.voiceName='$bundleVoiceName'"
            }
        }

        var requestLangNorm: String? = null
        var requestCountryNorm: String? = ""
        var requestLangSource: String? = null

        // From bundle language key
        if (bundleLang != null && bundleLang.isNotEmpty()) {
            requestLangNorm = normaliseLanguage(bundleLang)
            requestCountryNorm = normaliseCountry(bundleCountry ?: "")
            requestLangSource = "bundle.language='$bundleLang'"
        }
        // From request.language (fallback)
        val requestLang = request.language
        val requestCountry = request.country
        if (requestLangNorm == null && requestLang != null && requestLang.isNotEmpty()) {
            requestLangNorm = normaliseLanguage(requestLang)
            requestCountryNorm = normaliseCountry(requestCountry ?: "")
            requestLangSource = "request.language='$requestLang'"
        }

        // --- Decision logic ---
        var effectiveLang: String? = null
        var effectiveCountry: String? = ""
        var effectiveSource: String? = null

        if (voiceNameLang != null && requestLangNorm != null) {
            if (voiceNameLang == requestLangNorm) {
                // Both agree — high confidence, use voice name (has country too)
                effectiveLang = voiceNameLang
                effectiveCountry = voiceNameCountry
                effectiveSource = "$voiceNameSource (confirmed by $requestLangSource)"
                Log.d(TAG, "Voice name and request.language AGREE: $effectiveLang/$effectiveCountry")
            } else {
                // They DISAGREE — trust the voice name because:
                //  - setVoice() is more specific than setLanguage()
                //  - setLanguage() may have reverted to system default
                //  - The voice name encodes the actual voice that was selected
                effectiveLang = voiceNameLang
                effectiveCountry = voiceNameCountry
                effectiveSource = "$voiceNameSource (OVERRIDES $requestLangSource=$requestLangNorm)"
                Log.w(TAG, "Voice name ($voiceNameLang) DISAGREES with request.language ($requestLangNorm). " +
                    "Trusting voice name — request.language may be stale system default.")
            }
        } else if (voiceNameLang != null) {
            effectiveLang = voiceNameLang
            effectiveCountry = voiceNameCountry
            effectiveSource = voiceNameSource
        } else if (requestLangNorm != null) {
            effectiveLang = requestLangNorm
            effectiveCountry = requestCountryNorm
            effectiveSource = requestLangSource
        }

        // --- Last-resort fallback: auto-detect language from the text itself ---
        if (effectiveLang == null) {
            val detectedLocale = LanguageDetector.detectLanguage(this, text)
            if (detectedLocale != null) {
                effectiveLang = detectedLocale.language
                effectiveCountry = detectedLocale.country ?: ""
                effectiveSource = "auto-detect"
                Log.i(TAG, "Auto-detected language from text: $effectiveLang/$effectiveCountry")
            } else {
                Log.d(TAG, "Language auto-detection returned no result, will use current/default voice")
            }
        }

        val normLang = effectiveLang
        val normCountry = effectiveCountry ?: ""

        Log.i(TAG, "EFFECTIVE LANGUAGE: lang=$normLang country=$normCountry (source=$effectiveSource)")

        // --- Load the correct voice model ---
        // LANGUAGE takes priority over voice name. The voice name from the framework
        // may be stale (cached from system TTS settings), but the language we resolved
        // above is the most reliable signal.
        //
        // Strategy:
        //  1. If we have a language → load by locale first
        //  2. If locale load fails → try voice name
        //  3. If both fail → load active/default voice

        if (normLang != null) {
            val actualLoaded = loadedLocale
            val languageMismatch = actualLoaded == null || normLang != actualLoaded.language
            val countryMismatch = normCountry.isNotEmpty() && actualLoaded != null &&
                !normCountry.equals(actualLoaded.country, ignoreCase = true)
            if (languageMismatch || countryMismatch || engine == null) {
                Log.d(TAG, "Loading voice for effective language: $normLang/$normCountry " +
                    "(current loaded=$actualLoaded, engine=${if (engine != null) "active" else "null"})")
                val requestLocale = Locale(normLang, normCountry)
                val result = loadVoiceForLocale(requestLocale)
                if (result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    needsReload = false
                } else {
                    Log.w(TAG, "Failed to load voice for locale $normLang/$normCountry")
                }
            } else {
                Log.d(TAG, "Correct language already loaded: $actualLoaded")
                needsReload = false
            }
        }

        // If language-based loading didn't work, try voice name
        val requestedVoiceName = (request.voiceName ?: bundleVoiceName)?.takeIf { it.isNotEmpty() }
        if (engine == null && requestedVoiceName != null && requestedVoiceName != loadedVoiceName) {
            Log.d(TAG, "Trying voice name fallback: $requestedVoiceName")
            onLoadVoice(requestedVoiceName)
            needsReload = false
        }

        val currentEngine = engine
        if (currentEngine == null || needsReload) {
            Log.i(TAG, "No engine loaded (or reload needed), loading voice for request")

            // Try locale first, then voice name, then active voice
            var loaded = false
            if (!loaded && normLang != null) {
                val result = loadVoiceForLocale(Locale(normLang, normCountry))
                loaded = result != TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (!loaded && requestedVoiceName != null) {
                val voiceData = voiceManager.loadVoiceByName(requestedVoiceName)
                if (voiceData != null) {
                    engine?.close()
                    engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                    currentLocale = voiceData.locale
                    currentVoiceName = requestedVoiceName
                    loadedLocale = voiceData.locale
                    loadedVoiceName = requestedVoiceName
                    loaded = true
                    Log.i(TAG, "Loaded voice by name: $requestedVoiceName")
                }
            }
            if (!loaded) {
                try {
                    val voiceData = voiceManager.loadActiveVoice(voicePreferences)
                    if (voiceData != null) {
                        engine?.close()
                        engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                        currentLocale = voiceData.locale
                        currentVoiceName = voiceData.name
                        loadedLocale = voiceData.locale
                        loadedVoiceName = voiceData.name
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
        }

        synthesizeWithCurrentEngine(text, request, callback)
    }

    /**
     * Resolve the voice to load from direct params (piper_language, piper_country, piper_voice_name).
     * Returns true if a voice was successfully loaded, false otherwise.
     */
    private fun resolveVoiceFromDirectParams(lang: String?, country: String?, voiceName: String?): Boolean {
        // Try exact voice name first
        if (voiceName != null && voiceName.isNotEmpty()) {
            if (voiceName == loadedVoiceName && engine != null) {
                Log.d(TAG, "Direct params: voice '$voiceName' already loaded")
                return true
            }
            val voiceData = voiceManager.loadVoiceByName(voiceName)
            if (voiceData != null) {
                engine?.close()
                engine = PiperEngine(voiceData.config, voiceData.modelBytes)
                currentLocale = voiceData.locale
                currentVoiceName = voiceName
                loadedLocale = voiceData.locale
                loadedVoiceName = voiceName
                Log.i(TAG, "Direct params: loaded voice by name '$voiceName'")
                return true
            }
            Log.w(TAG, "Direct params: voice '$voiceName' not found on disk")
        }

        // Try by language/country
        if (lang != null && lang.isNotEmpty()) {
            val normLang = normaliseLanguage(lang)
            val normCountry = normaliseCountry(country ?: "")

            // Check if already loaded
            val loaded = loadedLocale
            if (loaded != null && engine != null &&
                normLang == loaded.language &&
                (normCountry.isEmpty() || normCountry.equals(loaded.country, ignoreCase = true))) {
                Log.d(TAG, "Direct params: locale $normLang/$normCountry already loaded")
                return true
            }

            val locale = Locale(normLang, normCountry)
            val result = loadVoiceForLocale(locale)
            if (result != TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.i(TAG, "Direct params: loaded voice for locale $normLang/$normCountry")
                return true
            }
            Log.w(TAG, "Direct params: no voice found for locale $normLang/$normCountry")
        }

        return false
    }

    /**
     * Perform synthesis with the currently loaded engine.
     * Shared between the direct-params path and the standard path.
     */
    private fun synthesizeWithCurrentEngine(text: String, request: SynthesisRequest, callback: SynthesisCallback) {
        try {
            val activeEngine = engine ?: run {
                Log.w(TAG, "Engine null in synthesizeWithCurrentEngine, returning silence")
                callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
                return
            }

            val speechRate = request.speechRate / 100.0f
            val lengthScale = 1.0f / speechRate.coerceIn(0.25f, 4.0f)

            val samples = activeEngine.synthesize(text = text, lengthScale = lengthScale)
            if (samples.isEmpty()) {
                callback.done()
                return
            }

            val pcmData = activeEngine.floatToInt16Pcm(samples)
            val sampleRate = activeEngine.sampleRate
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)

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
