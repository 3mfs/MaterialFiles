package com.moxapps.files.provider.root

import com.moxapps.files.provider.common.PosixFileAttributeView
import com.moxapps.files.provider.remote.RemoteInterface
import com.moxapps.files.provider.remote.RemotePosixFileAttributeView

open class RootPosixFileAttributeView(
    attributeView: PosixFileAttributeView
) : RemotePosixFileAttributeView(
    RemoteInterface { RootFileService.getRemotePosixFileAttributeViewInterface(attributeView) }
) {
    override fun name(): String {
        throw AssertionError()
    }
}
