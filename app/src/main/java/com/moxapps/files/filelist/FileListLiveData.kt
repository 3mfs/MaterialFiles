

package com.moxapps.files.filelist

import android.os.AsyncTask
import android.util.LruCache
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import com.moxapps.files.file.FileItem
import com.moxapps.files.file.loadFileItem
import com.moxapps.files.provider.common.newDirectoryStream
import com.moxapps.files.util.CloseableLiveData
import com.moxapps.files.util.Failure
import com.moxapps.files.util.Loading
import com.moxapps.files.util.Stateful
import com.moxapps.files.util.Success
import com.moxapps.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class FileListLiveData(private val path: Path) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    private val observer: PathObserver

    @Volatile
    private var isChangedWhileInactive = false

    init {
        loadValue()
        observer = PathObserver(path) { onChangeObserved() }
    }

    fun loadValue() {
        future?.cancel(true)
        val cached = value?.value ?: if (path.isRemotePath) directoryCache.get(cacheKey) else null
        value = Loading(cached)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value = try {
                path.newDirectoryStream().use { directoryStream ->
                    val fileList = mutableListOf<FileItem>()
                    for (path in directoryStream) {
                        try {
                            fileList.add(path.loadFileItem())
                        } catch (e: DirectoryIteratorException) {
                            // TODO: Ignoring such a file can be misleading and we need to support
                            //  files without information.
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                    if (path.isRemotePath) {
                        directoryCache.put(cacheKey, fileList)
                    }
                    Success(fileList as List<FileItem>)
                }
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private val cacheKey: String
        get() = path.toUri().toString()

    private fun onChangeObserved() {
        if (hasActiveObservers()) {
            loadValue()
        } else {
            isChangedWhileInactive = true
        }
    }

    override fun onActive() {
        if (isChangedWhileInactive) {
            loadValue()
            isChangedWhileInactive = false
        }
    }

    override fun close() {
        observer.close()
        future?.cancel(true)
    }

    companion object {
        private const val MAX_CACHED_DIRECTORIES = 64
        private val directoryCache = LruCache<String, List<FileItem>>(MAX_CACHED_DIRECTORIES)
    }
}
