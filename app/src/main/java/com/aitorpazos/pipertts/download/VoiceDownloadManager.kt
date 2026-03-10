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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading Piper voice models from HuggingFace.
 *
 * Catalog: https://huggingface.co/rhasspy/piper-voices/raw/main/voices.json
 * Files:   https://huggingface.co/rhasspy/piper-voices/resolve/main/{path}
 *
 * Uses org.json (built into Android) instead of Gson TypeToken to avoid
 * R8/ProGuard stripping generic type information at build time.
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

    private val voicesDir: File get() = File(context.filesDir, VOICES_DIR).also { it.mkdirs() }
    private val catalogCacheFile: File get() = File(context.cacheDir, CATALOG_CACHE_FILE)

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
        val isInstalled: Boolean,
        // Internal: paths to onnx and config files in the catalog
        val onnxPath: String = "",
        val configPath: String = "",
        val onnxSizeBytes: Long = 0,
        val configSizeBytes: Long = 0
    )

    interface DownloadProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * Fetch the voice catalog from HuggingFace (with 24h cache).
     *
     * Parses the JSON manually with org.json to avoid Gson TypeToken issues
     * that break under R8 minification.
     */
    suspend fun fetchCatalog(forceRefresh: Boolean = false): List<DownloadableVoice> =
        withContext(Dispatchers.IO) {
            val catalogJson = getCatalogJson(forceRefresh)
            val installedKeys = getInstalledVoiceKeys()
            parseCatalog(catalogJson, installedKeys)
        }

    private fun parseCatalog(json: String, installedKeys: Set<String>): List<DownloadableVoice> {
        val voices = mutableListOf<DownloadableVoice>()
        try {
            val root = JSONObject(json)
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                try {
                    val obj = root.getJSONObject(key)
                    val name = obj.optString("name", key)
                    val quality = obj.optString("quality", "medium")
                    val numSpeakers = obj.optInt("num_speakers", 1)

                    val lang = obj.optJSONObject("language")
                    val languageCode = lang?.optString("code", "") ?: ""
                    val languageEnglish = lang?.optString("name_english", "") ?: ""
                    val languageNative = lang?.optString("name_native", "") ?: ""
                    val countryEnglish = lang?.optString("country_english", "") ?: ""

                    // Find .onnx and .onnx.json file entries
                    val files = obj.optJSONObject("files")
                    var onnxPath = ""
                    var onnxSize = 0L
                    var configPath = ""
                    var configSize = 0L

                    if (files != null) {
                        val fileKeys = files.keys()
                        while (fileKeys.hasNext()) {
                            val fk = fileKeys.next()
                            val fi = files.optJSONObject(fk)
                            val size = fi?.optLong("size_bytes", 0) ?: 0
                            if (fk.endsWith(".onnx.json")) {
                                configPath = fk
                                configSize = size
                            } else if (fk.endsWith(".onnx")) {
                                onnxPath = fk
                                onnxSize = size
                            }
                        }
                    }

                    voices.add(
                        DownloadableVoice(
                            key = key,
                            displayName = name,
                            languageEnglish = languageEnglish,
                            languageNative = languageNative,
                            countryEnglish = countryEnglish,
                            languageCode = languageCode,
                            quality = quality,
                            numSpeakers = numSpeakers,
                            modelSizeBytes = onnxSize,
                            isInstalled = key in installedKeys,
                            onnxPath = onnxPath,
                            configPath = configPath,
                            onnxSizeBytes = onnxSize,
                            configSizeBytes = configSize
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse voice entry: $key", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse catalog JSON", e)
            throw RuntimeException("Failed to parse voice catalog: ${e.message}", e)
        }

        return voices.sortedWith(compareBy({ it.languageEnglish }, { it.displayName }, { it.quality }))
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
            val installedKeys = getInstalledVoiceKeys()
            val allVoices = parseCatalog(catalogJson, installedKeys)
            val voice = allVoices.find { it.key == voiceKey }
                ?: throw IllegalArgumentException("Voice not found: $voiceKey")

            if (voice.onnxPath.isEmpty()) {
                throw IllegalArgumentException("No .onnx file in voice: $voiceKey")
            }
            if (voice.configPath.isEmpty()) {
                throw IllegalArgumentException("No .onnx.json file in voice: $voiceKey")
            }

            val totalBytes = voice.onnxSizeBytes + voice.configSizeBytes

            // Download .onnx model
            val onnxDest = File(voicesDir, "$voiceKey.onnx")
            val configDest = File(voicesDir, "$voiceKey.onnx.json")

            Log.i(TAG, "Downloading voice: $voiceKey (${totalBytes / 1024 / 1024} MB)")

            downloadFile(
                url = FILES_BASE_URL + voice.onnxPath,
                dest = onnxDest,
                expectedSize = voice.onnxSizeBytes,
                listener = listener,
                totalBytes = totalBytes,
                startOffset = 0
            )

            downloadFile(
                url = FILES_BASE_URL + voice.configPath,
                dest = configDest,
                expectedSize = voice.configSizeBytes,
                listener = listener,
                totalBytes = totalBytes,
                startOffset = voice.onnxSizeBytes
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
            if (expectedSize > 0 && tmpFile.length() != expectedSize) {
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
