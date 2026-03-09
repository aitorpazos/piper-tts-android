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

package com.aitorpazos.pipertts.download

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading Piper voice models from HuggingFace.
 *
 * Catalog: https://huggingface.co/rhasspy/piper-voices/raw/main/voices.json
 * Files:   https://huggingface.co/rhasspy/piper-voices/resolve/main/{path}
 */
class VoiceDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceDownloadManager"
        private const val CATALOG_URL =
            "https://huggingface.co/rhasspy/piper-voices/raw/main/voices.json"
        private const val FILES_BASE_URL =
            "https://huggingface.co/rhasspy/piper-voices/resolve/main/"
        private const val VOICES_DIR = "voices"
        private const val CATALOG_CACHE_FILE = "voices_catalog.json"
        private const val CATALOG_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val gson = Gson()
    private val voicesDir: File get() = File(context.filesDir, VOICES_DIR).also { it.mkdirs() }
    private val catalogCacheFile: File get() = File(context.cacheDir, CATALOG_CACHE_FILE)

    data class CatalogVoice(
        val key: String,
        val name: String,
        val language: Language,
        val quality: String,
        val num_speakers: Int,
        val files: Map<String, FileInfo>,
        val aliases: List<String>
    )

    data class Language(
        val code: String,
        val family: String,
        val region: String,
        val name_native: String,
        val name_english: String,
        val country_english: String
    )

    data class FileInfo(
        val size_bytes: Long,
        val md5_digest: String
    )

    data class DownloadableVoice(
        val key: String,
        val displayName: String,
        val languageEnglish: String,
        val languageNative: String,
        val countryEnglish: String,
        val languageCode: String,
        val quality: String,
        val numSpeakers: Int,
        val modelSizeBytes: Long,
        val isInstalled: Boolean
    )

    interface DownloadProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * Fetch the voice catalog from HuggingFace (with 24h cache).
     */
    suspend fun fetchCatalog(forceRefresh: Boolean = false): List<DownloadableVoice> =
        withContext(Dispatchers.IO) {
            val catalogJson = getCatalogJson(forceRefresh)
            val type = object : TypeToken<Map<String, CatalogVoice>>() {}.type
            val catalog: Map<String, CatalogVoice> = gson.fromJson(catalogJson, type)
            val installedKeys = getInstalledVoiceKeys()

            catalog.values.map { voice ->
                val onnxFile = voice.files.entries.find { it.key.endsWith(".onnx") }
                DownloadableVoice(
                    key = voice.key,
                    displayName = voice.name,
                    languageEnglish = voice.language.name_english,
                    languageNative = voice.language.name_native,
                    countryEnglish = voice.language.country_english,
                    languageCode = voice.language.code,
                    quality = voice.quality,
                    numSpeakers = voice.num_speakers,
                    modelSizeBytes = onnxFile?.value?.size_bytes ?: 0,
                    isInstalled = voice.key in installedKeys
                )
            }.sortedWith(compareBy({ it.languageEnglish }, { it.displayName }, { it.quality }))
        }

    /**
     * Download a voice model (onnx + config json).
     */
    suspend fun downloadVoice(
        voiceKey: String,
        listener: DownloadProgressListener? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val catalogJson = getCatalogJson(false)
            val type = object : TypeToken<Map<String, CatalogVoice>>() {}.type
            val catalog: Map<String, CatalogVoice> = gson.fromJson(catalogJson, type)
            val voice = catalog[voiceKey]
                ?: throw IllegalArgumentException("Voice not found: $voiceKey")

            val onnxEntry = voice.files.entries.find { it.key.endsWith(".onnx") && !it.key.endsWith(".onnx.json") }
                ?: throw IllegalArgumentException("No .onnx file in voice: $voiceKey")
            val configEntry = voice.files.entries.find { it.key.endsWith(".onnx.json") }
                ?: throw IllegalArgumentException("No .onnx.json file in voice: $voiceKey")

            val totalBytes = onnxEntry.value.size_bytes + configEntry.value.size_bytes

            // Download .onnx model
            val onnxDest = File(voicesDir, "$voiceKey.onnx")
            val configDest = File(voicesDir, "$voiceKey.onnx.json")

            Log.i(TAG, "Downloading voice: $voiceKey (${totalBytes / 1024 / 1024} MB)")

            downloadFile(
                url = FILES_BASE_URL + onnxEntry.key,
                dest = onnxDest,
                expectedSize = onnxEntry.value.size_bytes,
                listener = listener,
                totalBytes = totalBytes,
                startOffset = 0
            )

            downloadFile(
                url = FILES_BASE_URL + configEntry.key,
                dest = configDest,
                expectedSize = configEntry.value.size_bytes,
                listener = listener,
                totalBytes = totalBytes,
                startOffset = onnxEntry.value.size_bytes
            )

            listener?.onComplete()
            Log.i(TAG, "Voice downloaded successfully: $voiceKey")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download voice: $voiceKey", e)
            listener?.onError(e.message ?: "Unknown error")
            // Clean up partial downloads
            File(voicesDir, "$voiceKey.onnx").delete()
            File(voicesDir, "$voiceKey.onnx.json").delete()
            false
        }
    }

    /**
     * Delete a downloaded voice.
     */
    fun deleteVoice(voiceKey: String): Boolean {
        val onnx = File(voicesDir, "$voiceKey.onnx")
        val config = File(voicesDir, "$voiceKey.onnx.json")
        val deletedOnnx = onnx.delete()
        val deletedConfig = config.delete()
        Log.i(TAG, "Deleted voice $voiceKey: onnx=$deletedOnnx config=$deletedConfig")
        return deletedOnnx || deletedConfig
    }

    /**
     * Get list of installed (downloaded) voice keys.
     */
    fun getInstalledVoiceKeys(): Set<String> {
        val files = voicesDir.listFiles() ?: return emptySet()
        return files
            .filter { it.name.endsWith(".onnx") && !it.name.endsWith(".onnx.json") }
            .map { it.nameWithoutExtension }
            .toSet()
    }

    private suspend fun getCatalogJson(forceRefresh: Boolean): String {
        // Check cache
        if (!forceRefresh && catalogCacheFile.exists()) {
            val age = System.currentTimeMillis() - catalogCacheFile.lastModified()
            if (age < CATALOG_MAX_AGE_MS) {
                return catalogCacheFile.readText()
            }
        }

        // Fetch from network
        val json = fetchUrl(CATALOG_URL)
        catalogCacheFile.writeText(json)
        return json
    }

    private fun fetchUrl(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "PiperTTS-Android/1.0")
        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(
        url: String,
        dest: File,
        expectedSize: Long,
        listener: DownloadProgressListener?,
        totalBytes: Long,
        startOffset: Long
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "PiperTTS-Android/1.0")

        try {
            if (conn.responseCode != 200) {
                throw RuntimeException("HTTP ${conn.responseCode} downloading $url")
            }

            val tmpFile = File(dest.parentFile, "${dest.name}.tmp")
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var downloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        listener?.onProgress(startOffset + downloaded, totalBytes)
                    }
                }
            }

            // Verify size
            if (tmpFile.length() != expectedSize) {
                tmpFile.delete()
                throw RuntimeException(
                    "Size mismatch for ${dest.name}: expected=$expectedSize actual=${tmpFile.length()}"
                )
            }

            // Atomic rename
            if (!tmpFile.renameTo(dest)) {
                tmpFile.delete()
                throw RuntimeException("Failed to rename temp file to ${dest.name}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
