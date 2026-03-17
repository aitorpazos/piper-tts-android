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

package com.aitorpazos.pipertts.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * JNI bridge to eSpeak-ng for text-to-IPA-phoneme conversion.
 *
 * eSpeak-ng is used to convert natural language text into IPA phoneme strings,
 * which are then mapped to phoneme IDs for Piper ONNX model inference.
 *
 * The native library and data files are bundled with the APK:
 * - libespeak_jni.so (per ABI)
 * - espeak-ng-data/ (in assets, extracted to app files dir on first use)
 */
object EspeakNative {

    private const val TAG = "EspeakNative"
    private var initialized = false

    init {
        try {
            System.loadLibrary("espeak_jni")
            Log.i(TAG, "Loaded libespeak_jni.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libespeak_jni.so", e)
        }
    }

    /**
     * Initialize eSpeak-ng with data files extracted from assets.
     * Must be called before [textToPhonemes].
     * Safe to call multiple times (no-op after first successful init).
     */
    @Synchronized
    fun initialize(context: Context): Boolean {
        if (initialized) return true

        val dataDir = extractDataIfNeeded(context)
        if (dataDir == null) {
            Log.e(TAG, "Failed to extract espeak-ng-data")
            return false
        }

        // espeak_Initialize expects the parent directory that contains "espeak-ng-data/"
        val result = nativeInitialize(dataDir.parent ?: dataDir.absolutePath)
        if (result) {
            initialized = true
            Log.i(TAG, "eSpeak-ng initialized with data at: ${dataDir.absolutePath}")
        } else {
            Log.e(TAG, "eSpeak-ng native initialization failed")
        }
        return result
    }

    /**
     * Convert text to IPA phoneme string using eSpeak-ng.
     *
     * @param voice eSpeak voice name (e.g. "en-us", "de", "fr-fr")
     * @param text Input text to phonemize
     * @return IPA phoneme string, or empty string on failure
     */
    fun textToPhonemes(voice: String, text: String): String {
        if (!initialized) {
            Log.w(TAG, "eSpeak-ng not initialized, returning empty phonemes")
            return ""
        }
        return nativeTextToPhonemes(voice, text)
    }

    /**
     * Release eSpeak-ng resources.
     */
    @Synchronized
    fun terminate() {
        if (initialized) {
            nativeTerminate()
            initialized = false
        }
    }

    /**
     * Extract espeak-ng-data from APK assets to the app's files directory.
     * Only extracts if not already present or if version changed.
     */
    private fun extractDataIfNeeded(context: Context): File? {
        val targetDir = File(context.filesDir, "espeak-ng-data")
        val versionFile = File(targetDir, ".version")
        val currentVersion = getAssetVersion(context)

        // Check if already extracted with current version
        if (targetDir.exists() && versionFile.exists()) {
            val extractedVersion = versionFile.readText().trim()
            if (extractedVersion == currentVersion) {
                Log.d(TAG, "espeak-ng-data already extracted (version: $currentVersion)")
                return targetDir
            }
        }

        Log.i(TAG, "Extracting espeak-ng-data to ${targetDir.absolutePath}")
        try {
            // Clean old data
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            extractAssetsRecursive(context, "espeak-ng-data", targetDir)

            // Write version marker
            versionFile.writeText(currentVersion)
            Log.i(TAG, "espeak-ng-data extracted successfully")
            return targetDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract espeak-ng-data", e)
            return null
        }
    }

    private fun extractAssetsRecursive(context: Context, assetPath: String, targetDir: File) {
        val assets = context.assets.list(assetPath) ?: return

        if (assets.isEmpty()) {
            // It's a file, copy it
            val targetFile = File(targetDir, File(assetPath).name)
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory
            val subDir = if (assetPath == "espeak-ng-data") targetDir
                         else File(targetDir, File(assetPath).name)
            subDir.mkdirs()

            for (child in assets) {
                extractAssetsRecursive(context, "$assetPath/$child", subDir)
            }
        }
    }

    private fun getAssetVersion(context: Context): String {
        return try {
            val versionInfo = context.assets.open("espeak-ng-data/.version").bufferedReader().readText().trim()
            versionInfo
        } catch (e: Exception) {
            // Use app version as fallback
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e2: Exception) {
                "unknown"
            }
        }
    }

    // ── Native methods ──────────────────────────────────────────────────

    private external fun nativeInitialize(dataPath: String): Boolean
    private external fun nativeTextToPhonemes(voiceName: String, text: String): String
    private external fun nativeTerminate()
}
