package com.moxapps.files.fileproperties.video

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Path
import com.moxapps.files.util.Stateful

class FilePropertiesVideoTabViewModel(path: Path) : ViewModel() {
    private val _videoInfoLiveData = VideoInfoLiveData(path)
    val videoInfoLiveData: LiveData<Stateful<VideoInfo>>
        get() = _videoInfoLiveData

    fun reload() {
        _videoInfoLiveData.loadValue()
    }

    override fun onCleared() {
        _videoInfoLiveData.close()
    }
}
