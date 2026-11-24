

package com.moxapps.files.compat

import android.system.ErrnoException
import com.moxapps.files.hiddenapi.RestrictedHiddenApi
import com.moxapps.files.util.lazyReflectedField

@RestrictedHiddenApi
private val functionNameField by lazyReflectedField(ErrnoException::class.java, "functionName")

val ErrnoException.functionNameCompat: String
    get() = functionNameField.get(this) as String
