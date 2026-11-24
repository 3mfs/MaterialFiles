package com.moxapps.files.provider.remote;

import com.moxapps.files.provider.remote.IRemoteFileSystem;
import com.moxapps.files.provider.remote.IRemoteFileSystemProvider;
import com.moxapps.files.provider.remote.IRemotePosixFileAttributeView;
import com.moxapps.files.provider.remote.IRemotePosixFileStore;
import com.moxapps.files.provider.remote.ParcelableObject;

interface IRemoteFileService {
    IRemoteFileSystemProvider getRemoteFileSystemProviderInterface(String scheme);

    IRemoteFileSystem getRemoteFileSystemInterface(in ParcelableObject fileSystem);

    IRemotePosixFileStore getRemotePosixFileStoreInterface(in ParcelableObject fileStore);

    IRemotePosixFileAttributeView getRemotePosixFileAttributeViewInterface(
        in ParcelableObject attributeView
    );
}
