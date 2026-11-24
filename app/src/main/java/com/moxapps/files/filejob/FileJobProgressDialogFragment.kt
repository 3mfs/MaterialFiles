/*
 * Copyright (c) 2026 Mox Apps
 * All Rights Reserved.
 */

package com.moxapps.files.filejob

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import com.moxapps.files.R
import com.moxapps.files.databinding.FileJobProgressDialogBinding
import com.moxapps.files.util.ParcelableArgs
import com.moxapps.files.util.args
import com.moxapps.files.util.layoutInflater
import com.moxapps.files.util.putArgs
import com.moxapps.files.util.show

class FileJobProgressDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: FileJobProgressDialogBinding
    private var hasSeenProgress = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = FileJobProgressDialogBinding.inflate(requireContext().layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(args.initialTitle)
            .setView(binding.root)
            .setPositiveButton(R.string.file_job_run_in_background) { _, _ -> dismiss() }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                FileJobService.cancelJob(args.jobId)
            }
            .create()
            .apply { setCanceledOnTouchOutside(false) }

        if (FileJobProgressTracker.isFinished(args.jobId)) {
            dismissAllowingStateLoss()
            return dialog
        }

        FileJobProgressTracker.observe(this) { map ->
            val info = map[args.jobId]
            if (info == null) {
                if (hasSeenProgress || FileJobProgressTracker.isFinished(args.jobId)) {
                    dismissAllowingStateLoss()
                }
                return@observe
            }
            hasSeenProgress = true
            if (info.isFinished) {
                dismissAllowingStateLoss()
                return@observe
            }
            dialog.setTitle(info.title)
            binding.currentFileText.text = info.currentFile ?: ""
            binding.currentFileText.isVisible = !info.currentFile.isNullOrEmpty()
            binding.progressBar.isIndeterminate = info.indeterminate
            if (!info.indeterminate) {
                binding.progressBar.max = info.max.coerceAtLeast(1)
                binding.progressBar.progress = info.progress.coerceIn(0, info.max.coerceAtLeast(1))
                val percent = ((info.progress.toLong() * 100L) / info.max.coerceAtLeast(1)).coerceIn(0, 100)
                binding.percentText.text = "$percent%"
                binding.percentText.isVisible = true
            } else {
                binding.percentText.isVisible = false
            }
            binding.statusText.text = info.text ?: ""
        }
        return dialog
    }

    companion object {
        fun show(jobId: Int, initialTitle: CharSequence, fragment: Fragment) {
            FileJobProgressDialogFragment().putArgs(Args(jobId, initialTitle)).show(fragment)
        }
    }

    @Parcelize
    class Args(
        val jobId: Int,
        val initialTitle: CharSequence
    ) : ParcelableArgs
}
