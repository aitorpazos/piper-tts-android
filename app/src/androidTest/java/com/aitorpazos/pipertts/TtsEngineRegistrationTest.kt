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

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests that verify the TTS engine is correctly registered
 * with Android's TTS framework.
 *
 * These tests run on a real device or emulator and validate the manifest
 * declarations, service resolution, and activity handlers that Android
 * requires to show a TTS engine in Settings → Text-to-Speech.
 */
@RunWith(AndroidJUnit4::class)
class TtsEngineRegistrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val packageManager = context.packageManager
    private val packageName = "com.aitorpazos.pipertts"

    /**
     * Android discovers TTS engines by querying for services that handle
     * the TTS_SERVICE intent. This test verifies our service is found.
     */
    @Test
    fun ttsServiceIsDiscoverableByPackageManager() {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val services = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)

        val ourService = services.find {
            it.serviceInfo.packageName == packageName
        }

        assertNotNull(
            "PiperTtsService must be discoverable via TTS_SERVICE intent. " +
                "Found ${services.size} TTS services: ${services.map { it.serviceInfo.packageName }}",
            ourService
        )
    }

    /**
     * The TTS service must declare the BIND_TEXT_TO_SPEECH_SERVICE permission.
     */
    @Test
    fun ttsServiceHasCorrectPermission() {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val services = packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)

        val ourService = services.find {
            it.serviceInfo.packageName == packageName
        }

        assertNotNull("Service not found", ourService)
        assertEquals(
            "android.permission.BIND_TEXT_TO_SPEECH_SERVICE",
            ourService!!.serviceInfo.permission
        )
    }

    /**
     * The TTS service must have meta-data pointing to @xml/tts_engine.
     */
    @Test
    fun ttsServiceHasTtsEngineMetadata() {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val services = packageManager.queryIntentServices(
            intent,
            PackageManager.GET_META_DATA
        )

        val ourService = services.find {
            it.serviceInfo.packageName == packageName
        }

        assertNotNull("Service not found", ourService)
        val metaData = ourService!!.serviceInfo.metaData
        assertNotNull("Service must have meta-data bundle", metaData)
        assertTrue(
            "Service must have 'android.speech.tts' meta-data",
            metaData.containsKey("android.speech.tts")
        )
    }

    /**
     * Android sends CHECK_TTS_DATA to verify voice data availability.
     * An activity must handle this intent for the engine to appear in settings.
     */
    @Test
    fun checkTtsDataActivityIsResolvable() {
        val intent = Intent("android.speech.tts.engine.CHECK_TTS_DATA")
        intent.setPackage(packageName)
        val activities = packageManager.queryIntentActivities(intent, 0)

        assertTrue(
            "CHECK_TTS_DATA activity must be resolvable. " +
                "This is required for the engine to appear in Android TTS settings.",
            activities.isNotEmpty()
        )

        val activity = activities.first()
        assertEquals(packageName, activity.activityInfo.packageName)
    }

    /**
     * Android sends INSTALL_TTS_DATA when voice data needs to be installed.
     */
    @Test
    fun installTtsDataActivityIsResolvable() {
        val intent = Intent("android.speech.tts.engine.INSTALL_TTS_DATA")
        intent.setPackage(packageName)
        val activities = packageManager.queryIntentActivities(intent, 0)

        assertTrue(
            "INSTALL_TTS_DATA activity must be resolvable",
            activities.isNotEmpty()
        )
    }

    /**
     * Android sends GET_SAMPLE_TEXT to get text for the "Listen" button.
     */
    @Test
    fun getSampleTextActivityIsResolvable() {
        val intent = Intent("android.speech.tts.engine.GET_SAMPLE_TEXT")
        intent.setPackage(packageName)
        val activities = packageManager.queryIntentActivities(intent, 0)

        assertTrue(
            "GET_SAMPLE_TEXT activity must be resolvable",
            activities.isNotEmpty()
        )
    }

    /**
     * Verify the engine appears in Android's list of available TTS engines.
     * This is the ultimate test — if this passes, the engine will show in Settings.
     */
    @Test
    fun engineAppearsInAndroidTtsEngineList() {
        val tts = TextToSpeech(context, null)
        try {
            val engines = tts.engines
            val ourEngine = engines.find { it.name == packageName }

            assertNotNull(
                "Piper TTS must appear in TextToSpeech.getEngines(). " +
                    "Found ${engines.size} engines: ${engines.map { it.name }}",
                ourEngine
            )

            assertEquals("Piper TTS", ourEngine!!.label)
        } finally {
            tts.shutdown()
        }
    }

    /**
     * Verify the service class name is correct (not obfuscated by R8).
     */
    @Test
    fun serviceClassNameIsNotObfuscated() {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val services = packageManager.queryIntentServices(intent, 0)

        val ourService = services.find {
            it.serviceInfo.packageName == packageName
        }

        assertNotNull("Service not found", ourService)
        assertEquals(
            "Service class name must not be obfuscated by R8",
            "com.aitorpazos.pipertts.service.PiperTtsService",
            ourService!!.serviceInfo.name
        )
    }
}
