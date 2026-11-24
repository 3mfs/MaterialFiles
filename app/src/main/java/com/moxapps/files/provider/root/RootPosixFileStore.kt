package com.moxapps.files.provider.root

import com.moxapps.files.provider.common.PosixFileStore
import com.moxapps.files.provider.remote.RemoteInterface
import com.moxapps.files.provider.remote.RemotePosixFileStore

class RootPosixFileStore(fileStore: PosixFileStore) : RemotePosixFileStore(
    RemoteInterface { RootFileService.getRemotePosixFileStoreInterface(fileStore) }
)
