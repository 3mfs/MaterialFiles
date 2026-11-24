package com.moxapps.files.compat

object UserHandleCompat {
    // @see UserHandle.PER_USER_RANGE
    private const val PER_USER_RANGE = 100000

    fun getAppId(uid: Int): Int = uid % PER_USER_RANGE
}
