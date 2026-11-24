package com.moxapps.files.fileproperties.image

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import java8.nio.file.Path
import com.moxapps.files.file.MimeType
import com.moxapps.files.util.Stateful

class FilePropertiesImageTabViewModel(path: Path, mimeType: MimeType) : ViewModel() {
    private val _imageInfoLiveData = ImageInfoLiveData(path, mimeType)
    val imageInfoLiveData: LiveData<Stateful<ImageInfo>>
        get() = _imageInfoLiveData

    fun reload() {
        _imageInfoLiveData.loadValue()
    }

    override fun onCleared() {
        _imageInfoLiveData.close()
    }
}
