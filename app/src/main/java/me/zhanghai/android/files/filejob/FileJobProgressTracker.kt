/*
 * Copyright (c) 2026 Mox Apps
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class FileJobProgress(
    val jobId: Int,
    val title: CharSequence,
    val text: CharSequence?,
    val currentFile: CharSequence?,
    val max: Int,
    val progress: Int,
    val indeterminate: Boolean,
    val isFinished: Boolean = false
)

object FileJobProgressTracker {
    private val progresses = ConcurrentHashMap<Int, FileJobProgress>()
    private val finishedJobs = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val liveData = MutableLiveData<Map<Int, FileJobProgress>>(emptyMap())

    fun update(progress: FileJobProgress) {
        finishedJobs.remove(progress.jobId)
        progresses[progress.jobId] = progress
        liveData.postValue(HashMap(progresses))
    }

    fun finish(jobId: Int) {
        if (finishedJobs.size > 64) {
            finishedJobs.clear()
        }
        finishedJobs.add(jobId)
        val existing = progresses[jobId]
        if (existing != null) {
            progresses[jobId] = existing.copy(isFinished = true)
            liveData.postValue(HashMap(progresses))
        }
        progresses.remove(jobId)
        liveData.postValue(HashMap(progresses))
    }

    fun isFinished(jobId: Int): Boolean = finishedJobs.contains(jobId)

    fun observe(owner: LifecycleOwner, observer: (Map<Int, FileJobProgress>) -> Unit) {
        liveData.observe(owner, observer)
    }
}
