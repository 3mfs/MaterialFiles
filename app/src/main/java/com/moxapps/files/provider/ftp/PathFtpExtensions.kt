package com.moxapps.files.provider.ftp

import java8.nio.file.Path
import com.moxapps.files.provider.ftp.client.Authority

fun Authority.createFtpRootPath(): Path =
    FtpFileSystemProvider.getOrNewFileSystem(this).rootDirectory
