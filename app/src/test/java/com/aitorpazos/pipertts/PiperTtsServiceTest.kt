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

package com.aitorpazos.pipertts

import com.aitorpazos.pipertts.service.PiperTtsService
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the PiperTtsService static/companion methods.
 *
 * These verify the language normalisation logic that Android's TTS framework
 * relies on when probing the engine.
 */
class PiperTtsServiceTest {

    @Test
    fun `normaliseLanguage handles 2-letter codes`() {
        assertEquals("en", PiperTtsService.normaliseLanguage("en"))
        assertEquals("es", PiperTtsService.normaliseLanguage("es"))
        assertEquals("fr", PiperTtsService.normaliseLanguage("fr"))
        assertEquals("de", PiperTtsService.normaliseLanguage("de"))
    }

    @Test
    fun `normaliseLanguage handles 3-letter ISO 639-2 codes`() {
        assertEquals("en", PiperTtsService.normaliseLanguage("eng"))
        assertEquals("es", PiperTtsService.normaliseLanguage("spa"))
        assertEquals("fr", PiperTtsService.normaliseLanguage("fra"))
        assertEquals("de", PiperTtsService.normaliseLanguage("deu"))
    }

    @Test
    fun `normaliseLanguage handles locale strings with underscore`() {
        assertEquals("en", PiperTtsService.normaliseLanguage("en_US"))
        assertEquals("es", PiperTtsService.normaliseLanguage("es_ES"))
        assertEquals("fr", PiperTtsService.normaliseLanguage("fr_FR"))
    }

    @Test
    fun `normaliseLanguage handles locale strings with hyphen`() {
        assertEquals("en", PiperTtsService.normaliseLanguage("en-US"))
        assertEquals("es", PiperTtsService.normaliseLanguage("es-ES"))
    }

    @Test
    fun `normaliseLanguage handles uppercase input`() {
        assertEquals("en", PiperTtsService.normaliseLanguage("EN"))
        assertEquals("en", PiperTtsService.normaliseLanguage("ENG"))
        assertEquals("en", PiperTtsService.normaliseLanguage("EN_US"))
    }

    @Test
    fun `normaliseLanguage handles mixed case`() {
        assertEquals("en", PiperTtsService.normaliseLanguage("En"))
        assertEquals("en", PiperTtsService.normaliseLanguage("Eng"))
    }

    @Test
    fun `normaliseCountry handles standard codes`() {
        assertEquals("US", PiperTtsService.normaliseCountry("US"))
        assertEquals("US", PiperTtsService.normaliseCountry("us"))
        assertEquals("GB", PiperTtsService.normaliseCountry("gb"))
    }

    @Test
    fun `normaliseCountry handles empty string`() {
        assertEquals("", PiperTtsService.normaliseCountry(""))
    }
}
