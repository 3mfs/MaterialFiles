package com.moxapps.files.fileproperties.video

import android.media.MediaMetadataRetriever
import android.os.AsyncTask
import android.util.Size
import java.time.Duration
import java8.nio.file.Path
import com.moxapps.files.compat.use
import com.moxapps.files.fileproperties.PathObserverLiveData
import com.moxapps.files.fileproperties.date
import com.moxapps.files.fileproperties.extractMetadataNotBlank
import com.moxapps.files.fileproperties.location
import com.moxapps.files.util.Failure
import com.moxapps.files.util.Loading
import com.moxapps.files.util.Stateful
import com.moxapps.files.util.Success
import com.moxapps.files.util.setDataSource
import com.moxapps.files.util.valueCompat

class VideoInfoLiveData(path: Path) : PathObserverLiveData<Stateful<VideoInfo>>(path) {
    init {
        loadValue()
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val videoInfo = MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(path)
                    val title = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_TITLE
                    )
                    val width = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )?.toIntOrNull()
                    val height = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )?.toIntOrNull()
                    val dimensions = if (width != null && height != null) {
                        Size(width, height)
                    } else {
                        null
                    }
                    val duration = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull()?.let { Duration.ofMillis(it) }
                    val date = retriever.date
                    val location = retriever.location
                    val bitRate = retriever.extractMetadataNotBlank(
                        MediaMetadataRetriever.METADATA_KEY_BITRATE
                    )?.toLongOrNull()
                    VideoInfo(title, dimensions, duration, date, location, bitRate)
                }
                Success(videoInfo)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
