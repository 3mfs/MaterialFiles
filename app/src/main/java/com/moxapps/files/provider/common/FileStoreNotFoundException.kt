package com.moxapps.files.provider.common

import java8.nio.file.FileSystemException

class FileStoreNotFoundException(file: String?) : FileSystemException(file)
