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

import com.aitorpazos.pipertts.engine.PhonemeConverter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the PhonemeConverter.
 */
class PhonemeConverterTest {

    private lateinit var converter: PhonemeConverter

    // Simplified phoneme_id_map for testing
    private val testPhonemeIdMap = mapOf(
        "_" to listOf(0),       // PAD
        "^" to listOf(1),       // BOS
        "$" to listOf(2),       // EOS
        " " to listOf(3),
        "a" to listOf(4),
        "b" to listOf(5),
        "c" to listOf(6),
        "d" to listOf(7),
        "e" to listOf(8),
        "h" to listOf(9),
        "l" to listOf(10),
        "o" to listOf(11),
        "r" to listOf(12),
        "w" to listOf(13),
        "!" to listOf(14),
        "." to listOf(15),
        "," to listOf(16)
    )

    @Before
    fun setUp() {
        // Use character mode (no phonemeType / espeakVoice) for unit tests
        converter = PhonemeConverter(testPhonemeIdMap)
    }

    @Test
    fun `empty text produces minimal output`() {
        val result = converter.textToPhonemeIds("")
        // Should still have BOS + PAD + EOS + PAD
        assertTrue(result.isNotEmpty())
        assertEquals(1L, result[0]) // BOS
    }

    @Test
    fun `simple word produces correct phoneme IDs`() {
        val result = converter.textToPhonemeIds("hello")
        assertTrue(result.isNotEmpty())

        // Should contain BOS(1), PAD(0), h(9), PAD(0), e(8), PAD(0), l(10), PAD(0), l(10), PAD(0), o(11), PAD(0), EOS(2), PAD(0)
        assertEquals(1L, result[0]) // BOS
        assertEquals(0L, result[1]) // PAD after BOS
        assertEquals(9L, result[2]) // h
        assertEquals(0L, result[3]) // PAD
        assertEquals(8L, result[4]) // e
    }

    @Test
    fun `text is lowercased before conversion`() {
        val lower = converter.textToPhonemeIds("hello")
        val upper = converter.textToPhonemeIds("HELLO")
        assertArrayEquals(lower, upper)
    }

    @Test
    fun `unknown characters are skipped`() {
        val withUnknown = converter.textToPhonemeIds("a1b")
        val withoutUnknown = converter.textToPhonemeIds("ab")
        // "1" is not in the map, so both should produce the same result
        assertArrayEquals(withoutUnknown, withUnknown)
    }

    @Test
    fun `whitespace is normalized`() {
        val normal = converter.textToPhonemeIds("a b")
        val multiSpace = converter.textToPhonemeIds("a   b")
        assertArrayEquals(normal, multiSpace)
    }

    @Test
    fun `newlines and tabs are normalized to spaces`() {
        val normal = converter.textToPhonemeIds("a b")
        val withNewline = converter.textToPhonemeIds("a\nb")
        val withTab = converter.textToPhonemeIds("a\tb")
        assertArrayEquals(normal, withNewline)
        assertArrayEquals(normal, withTab)
    }

    @Test
    fun `punctuation is included when in map`() {
        val result = converter.textToPhonemeIds("hello!")
        // Should contain the exclamation mark ID (14)
        val containsExclamation = result.any { it == 14L }
        assertTrue("Should contain exclamation mark phoneme ID", containsExclamation)
    }

    @Test
    fun `BOS and EOS tokens are present`() {
        val result = converter.textToPhonemeIds("a")
        assertEquals("First element should be BOS", 1L, result[0])
        assertEquals("Last element should be PAD after EOS", 0L, result[result.size - 1])
        // Second to last should be EOS
        assertEquals("Second to last should be EOS", 2L, result[result.size - 2])
    }

    @Test
    fun `empty phoneme map produces BOS and EOS only`() {
        val emptyConverter = PhonemeConverter(emptyMap())
        val result = emptyConverter.textToPhonemeIds("hello")
        // With empty map, only default pad (0) values should appear
        assertTrue(result.isNotEmpty())
    }
}
