package com.moxapps.files.provider.webdav.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.moxapps.files.provider.common.UriAuthority
import com.moxapps.files.util.takeIfNotEmpty

@Parcelize
data class Authority(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val username: String
) : Parcelable {
    fun toUriAuthority(): UriAuthority {
        val userInfo = username.takeIfNotEmpty()
        val uriPort = port.takeIf { it != protocol.defaultPort }
        return UriAuthority(userInfo, host, uriPort)
    }

    override fun toString(): String = toUriAuthority().toString()
}
