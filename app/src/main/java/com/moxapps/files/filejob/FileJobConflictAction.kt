package com.moxapps.files.filejob

enum class FileJobConflictAction {
    MERGE_OR_REPLACE,
    RENAME,
    SKIP,
    CANCEL,
    CANCELED
}
