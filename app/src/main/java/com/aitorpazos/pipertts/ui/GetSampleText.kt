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

package com.aitorpazos.pipertts.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.aitorpazos.pipertts.service.PiperTtsService

/**
 * Handles the android.speech.tts.engine.GET_SAMPLE_TEXT intent.
 *
 * Android sends this to get a sample text string for the "Listen to an example"
 * button in TTS settings. The engine should return appropriate sample text for
 * the requested language.
 */
class GetSampleText : Activity() {

    private val sampleTexts = mapOf(
        "en" to "This is Piper, an open-source text to speech engine running locally on your device.",
        "es" to "Este es Piper, un motor de texto a voz de código abierto que funciona localmente en tu dispositivo.",
        "fr" to "Ceci est Piper, un moteur de synthèse vocale open source fonctionnant localement sur votre appareil.",
        "de" to "Dies ist Piper, eine Open-Source Text-to-Speech-Engine, die lokal auf Ihrem Gerät läuft.",
        "it" to "Questo è Piper, un motore di sintesi vocale open source che funziona localmente sul tuo dispositivo.",
        "pt" to "Este é o Piper, um motor de texto para fala de código aberto que funciona localmente no seu dispositivo.",
        "nl" to "Dit is Piper, een open-source tekst-naar-spraak engine die lokaal op uw apparaat draait.",
        "pl" to "To jest Piper, otwarty silnik zamiany tekstu na mowę działający lokalnie na Twoim urządzeniu.",
        "ru" to "Это Piper, движок синтеза речи с открытым исходным кодом, работающий локально на вашем устройстве.",
        "uk" to "Це Piper, двигун синтезу мовлення з відкритим кодом, що працює локально на вашому пристрої.",
        "ca" to "Aquest és Piper, un motor de text a veu de codi obert que funciona localment al vostre dispositiu.",
        "da" to "Dette er Piper, en open source tekst-til-tale motor der kører lokalt på din enhed.",
        "fi" to "Tämä on Piper, avoimen lähdekoodin tekstistä puheeksi -moottori, joka toimii paikallisesti laitteellasi.",
        "el" to "Αυτό είναι το Piper, μια μηχανή μετατροπής κειμένου σε ομιλία ανοιχτού κώδικα.",
        "hu" to "Ez a Piper, egy nyílt forráskódú szövegfelolvasó motor, amely helyileg fut az eszközén.",
        "no" to "Dette er Piper, en åpen kildekode tekst-til-tale motor som kjører lokalt på enheten din.",
        "sv" to "Det här är Piper, en text-till-tal-motor med öppen källkod som körs lokalt på din enhet.",
        "tr" to "Bu, cihazınızda yerel olarak çalışan açık kaynaklı bir metin-konuşma motoru olan Piper'dır.",
        "vi" to "Đây là Piper, một công cụ chuyển văn bản thành giọng nói mã nguồn mở."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get requested language from intent
        val language = intent?.getStringExtra("language")
        val lang2 = if (language != null) {
            PiperTtsService.normaliseLanguage(language)
        } else {
            "en"
        }

        val sampleText = sampleTexts[lang2]
            ?: sampleTexts["en"]
            ?: "This is a sample of Piper text to speech."

        val result = Intent()
        result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleText)
        setResult(RESULT_OK, result)
        finish()
    }
}
