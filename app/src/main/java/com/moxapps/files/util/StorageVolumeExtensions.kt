package com.moxapps.files.util

import android.os.storage.StorageVolume
import com.moxapps.files.compat.directoryCompat

val StorageVolume.isMounted: Boolean
    get() = directoryCompat != null
