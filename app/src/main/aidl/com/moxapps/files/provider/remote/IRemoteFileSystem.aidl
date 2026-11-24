package com.moxapps.files.provider.remote;

import com.moxapps.files.provider.remote.ParcelableException;

interface IRemoteFileSystem {
    void close(out ParcelableException exception);
}
