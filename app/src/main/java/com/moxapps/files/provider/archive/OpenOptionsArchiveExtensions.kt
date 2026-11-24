

package com.moxapps.files.provider.archive

import java8.nio.file.StandardOpenOption
import com.moxapps.files.provider.common.OpenOptions

internal fun OpenOptions.checkForArchive(isWritable: Boolean) {
    if (!isWritable) {
        if (write) {
            throw UnsupportedOperationException(StandardOpenOption.WRITE.toString())
        }
        if (truncateExisting) {
            throw UnsupportedOperationException(StandardOpenOption.TRUNCATE_EXISTING.toString())
        }
        if (create) {
            throw UnsupportedOperationException(StandardOpenOption.CREATE.toString())
        }
        if (createNew) {
            throw UnsupportedOperationException(StandardOpenOption.CREATE_NEW.toString())
        }
    }
    if (append) {
        throw UnsupportedOperationException(StandardOpenOption.APPEND.toString())
    }
    if (deleteOnClose) {
        throw UnsupportedOperationException(StandardOpenOption.DELETE_ON_CLOSE.toString())
    }
    if (sync) {
        throw UnsupportedOperationException(StandardOpenOption.SYNC.toString())
    }
    if (dsync) {
        throw UnsupportedOperationException(StandardOpenOption.DSYNC.toString())
    }
}
