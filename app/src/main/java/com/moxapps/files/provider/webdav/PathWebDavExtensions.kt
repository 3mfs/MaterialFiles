package com.moxapps.files.provider.webdav

import java8.nio.file.Path
import com.moxapps.files.provider.webdav.client.Authority

fun Authority.createWebDavRootPath(): Path =
    WebDavFileSystemProvider.getOrNewFileSystem(this).rootDirectory
