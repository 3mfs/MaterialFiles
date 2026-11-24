package com.moxapps.files.fileproperties.apk

import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData
import com.moxapps.files.app.packageManager
import com.moxapps.files.util.Failure
import com.moxapps.files.util.Loading
import com.moxapps.files.util.Stateful
import com.moxapps.files.util.Success
import com.moxapps.files.util.getPermissionInfoOrNull
import com.moxapps.files.util.valueCompat

class PermissionListLiveData(
    private val permissionNames: Array<String>
) : MutableLiveData<Stateful<List<PermissionItem>>>() {
    init {
        loadValue()
    }

    private fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val permissions = permissionNames.map { name ->
                    val packageManager = packageManager
                    val permissionInfo = packageManager.getPermissionInfoOrNull(name, 0)
                    val label = permissionInfo?.loadLabel(packageManager)?.toString()
                        .takeIf { it != name }
                    val description = permissionInfo?.loadDescription(packageManager)?.toString()
                    PermissionItem(name, permissionInfo, label, description)
                }
                Success(permissions)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
