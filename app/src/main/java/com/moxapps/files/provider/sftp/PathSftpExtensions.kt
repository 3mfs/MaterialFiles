package com.moxapps.files.provider.sftp

import java8.nio.file.Path
import com.moxapps.files.provider.sftp.client.Authority

fun Authority.createSftpRootPath(): Path =
    SftpFileSystemProvider.getOrNewFileSystem(this).rootDirectory
