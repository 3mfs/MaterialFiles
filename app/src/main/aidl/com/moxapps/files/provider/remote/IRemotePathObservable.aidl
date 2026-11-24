package com.moxapps.files.provider.remote;

import com.moxapps.files.provider.remote.ParcelableException;
import com.moxapps.files.util.RemoteCallback;

interface IRemotePathObservable {
    void addObserver(in RemoteCallback observer);

    void close(out ParcelableException exception);
}
