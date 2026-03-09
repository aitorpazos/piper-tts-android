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

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.aitorpazos.pipertts.R
import com.aitorpazos.pipertts.databinding.ActivityVoiceListBinding
import com.aitorpazos.pipertts.download.VoiceDownloadManager
import com.aitorpazos.pipertts.download.VoiceDownloadManager.DownloadableVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity to browse, download, and manage Piper voice models.
 */
class VoiceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceListBinding
    private lateinit var downloadManager: VoiceDownloadManager
    private lateinit var adapter: VoiceAdapter

    private var allVoices: List<DownloadableVoice> = emptyList()
    private var filterInstalled = false
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloadManager = VoiceDownloadManager(this)

        adapter = VoiceAdapter(
            onDownload = { voice -> downloadVoice(voice) },
            onDelete = { voice -> confirmDeleteVoice(voice) }
        )

        binding.rvVoices.layoutManager = LinearLayoutManager(this)
        binding.rvVoices.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.chipInstalled.setOnCheckedChangeListener { _, isChecked ->
            filterInstalled = isChecked
            applyFilter()
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })

        loadCatalog(forceRefresh = false)
    }

    private fun loadCatalog(forceRefresh: Boolean) {
        binding.progressCatalog.visibility = View.VISIBLE
        binding.tvCatalogStatus.text = getString(R.string.loading_catalog)

        lifecycleScope.launch {
            try {
                val voices = downloadManager.fetchCatalog(forceRefresh)
                allVoices = voices

                val installedCount = voices.count { it.isInstalled }
                val languageCount = voices.map { it.languageEnglish }.distinct().size

                binding.tvCatalogStatus.text = getString(
                    R.string.catalog_status,
                    voices.size,
                    languageCount,
                    installedCount
                )

                applyFilter()
            } catch (e: Exception) {
                binding.tvCatalogStatus.text = getString(R.string.catalog_error, e.message ?: "Unknown")
                Toast.makeText(
                    this@VoiceListActivity,
                    "Failed to load catalog: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressCatalog.visibility = View.GONE
            }
        }
    }

    private fun applyFilter() {
        var filtered = allVoices

        if (filterInstalled) {
            filtered = filtered.filter { it.isInstalled }
        }

        if (searchQuery.isNotEmpty()) {
            val query = searchQuery.lowercase()
            filtered = filtered.filter { voice ->
                voice.languageEnglish.lowercase().contains(query) ||
                voice.languageNative.lowercase().contains(query) ||
                voice.countryEnglish.lowercase().contains(query) ||
                voice.displayName.lowercase().contains(query) ||
                voice.languageCode.lowercase().contains(query) ||
                voice.key.lowercase().contains(query)
            }
        }

        adapter.submitList(filtered)

        if (filtered.isEmpty() && allVoices.isNotEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvVoices.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvVoices.visibility = View.VISIBLE
        }
    }

    private fun downloadVoice(voice: DownloadableVoice) {
        adapter.setDownloading(voice.key, true, 0)

        lifecycleScope.launch {
            val success = downloadManager.downloadVoice(
                voice.key,
                object : VoiceDownloadManager.DownloadProgressListener {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long) {
                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                        lifecycleScope.launch(Dispatchers.Main) {
                            adapter.setDownloading(voice.key, true, pct)
                        }
                    }

                    override fun onComplete() {
                        lifecycleScope.launch(Dispatchers.Main) {
                            adapter.setDownloading(voice.key, false)
                        }
                    }

                    override fun onError(error: String) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            adapter.setDownloading(voice.key, false)
                            Toast.makeText(
                                this@VoiceListActivity,
                                "Download failed: $error",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )

            if (success) {
                // Refresh to update installed state
                refreshInstalledState()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@VoiceListActivity,
                        "${voice.languageEnglish} - ${voice.displayName} installed!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun confirmDeleteVoice(voice: DownloadableVoice) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_voice_title)
            .setMessage(getString(R.string.delete_voice_message, voice.languageEnglish, voice.displayName))
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteVoice(voice) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteVoice(voice: DownloadableVoice) {
        downloadManager.deleteVoice(voice.key)
        refreshInstalledState()
        Toast.makeText(this, "Voice deleted", Toast.LENGTH_SHORT).show()
    }

    private fun refreshInstalledState() {
        lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                downloadManager.getInstalledVoiceKeys()
            }
            allVoices = allVoices.map { it.copy(isInstalled = it.key in installed) }
            applyFilter()

            val installedCount = allVoices.count { it.isInstalled }
            val languageCount = allVoices.map { it.languageEnglish }.distinct().size
            binding.tvCatalogStatus.text = getString(
                R.string.catalog_status,
                allVoices.size,
                languageCount,
                installedCount
            )
        }
    }
}
