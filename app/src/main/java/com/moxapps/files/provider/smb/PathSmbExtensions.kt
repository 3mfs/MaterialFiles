package com.moxapps.files.provider.smb

import java8.nio.file.Path
import com.moxapps.files.provider.smb.client.Authority

fun Authority.createSmbRootPath(): Path =
    SmbFileSystemProvider.getOrNewFileSystem(this).rootDirectory
