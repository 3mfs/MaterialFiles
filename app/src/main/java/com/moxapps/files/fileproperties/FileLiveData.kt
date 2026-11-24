package com.moxapps.files.fileproperties

import android.os.AsyncTask
import java8.nio.file.Path
import com.moxapps.files.file.FileItem
import com.moxapps.files.file.loadFileItem
import com.moxapps.files.util.Failure
import com.moxapps.files.util.Loading
import com.moxapps.files.util.Stateful
import com.moxapps.files.util.Success
import com.moxapps.files.util.valueCompat

class FileLiveData private constructor(
    path: Path,
    file: FileItem?
) : PathObserverLiveData<Stateful<FileItem>>(path) {
    constructor(path: Path) : this(path, null)

    constructor(file: FileItem) : this(file.path, file)

    init {
        if (file != null) {
            value = Success(file)
        } else {
            loadValue()
        }
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val file = path.loadFileItem()
                Success(file)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
