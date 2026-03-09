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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aitorpazos.pipertts.R
import com.aitorpazos.pipertts.databinding.ItemVoiceBinding
import com.aitorpazos.pipertts.download.VoiceDownloadManager.DownloadableVoice

class VoiceAdapter(
    private val onDownload: (DownloadableVoice) -> Unit,
    private val onDelete: (DownloadableVoice) -> Unit
) : ListAdapter<DownloadableVoice, VoiceAdapter.VoiceViewHolder>(VoiceDiffCallback()) {

    // Track which voices are currently downloading
    private val downloadingKeys = mutableSetOf<String>()
    private val downloadProgress = mutableMapOf<String, Int>()

    fun setDownloading(key: String, isDownloading: Boolean, progress: Int = 0) {
        if (isDownloading) {
            downloadingKeys.add(key)
            downloadProgress[key] = progress
        } else {
            downloadingKeys.remove(key)
            downloadProgress.remove(key)
        }
        val position = currentList.indexOfFirst { it.key == key }
        if (position >= 0) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        val binding = ItemVoiceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VoiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VoiceViewHolder(
        private val binding: ItemVoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(voice: DownloadableVoice) {
            val ctx = binding.root.context

            binding.tvLanguage.text = buildString {
                append(voice.languageEnglish)
                append(" (")
                append(voice.countryEnglish)
                append(")")
            }

            binding.tvVoiceName.text = buildString {
                append(voice.displayName)
                append(" · ")
                append(voice.languageNative)
                if (voice.numSpeakers > 1) {
                    append(" · ${voice.numSpeakers} speakers")
                }
            }

            binding.chipQuality.text = voice.quality.replace("_", " ")

            val sizeMb = voice.modelSizeBytes / (1024.0 * 1024.0)
            binding.tvSize.text = String.format("%.1f MB", sizeMb)

            val isDownloading = voice.key in downloadingKeys

            if (voice.isInstalled) {
                binding.btnDownload.text = ctx.getString(R.string.btn_installed)
                binding.btnDownload.isEnabled = false
                binding.btnDelete.visibility = View.VISIBLE
                binding.progressDownload.visibility = View.GONE
            } else if (isDownloading) {
                val progress = downloadProgress[voice.key] ?: 0
                binding.btnDownload.text = ctx.getString(R.string.btn_downloading)
                binding.btnDownload.isEnabled = false
                binding.btnDelete.visibility = View.GONE
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.isIndeterminate = progress <= 0
                if (progress > 0) {
                    binding.progressDownload.isIndeterminate = false
                    binding.progressDownload.progress = progress
                }
            } else {
                binding.btnDownload.text = ctx.getString(R.string.btn_download)
                binding.btnDownload.isEnabled = true
                binding.btnDelete.visibility = View.GONE
                binding.progressDownload.visibility = View.GONE
            }

            binding.btnDownload.setOnClickListener {
                if (!voice.isInstalled && !isDownloading) {
                    onDownload(voice)
                }
            }

            binding.btnDelete.setOnClickListener {
                if (voice.isInstalled) {
                    onDelete(voice)
                }
            }
        }
    }

    class VoiceDiffCallback : DiffUtil.ItemCallback<DownloadableVoice>() {
        override fun areItemsTheSame(old: DownloadableVoice, new: DownloadableVoice) =
            old.key == new.key

        override fun areContentsTheSame(old: DownloadableVoice, new: DownloadableVoice) =
            old == new
    }
}
