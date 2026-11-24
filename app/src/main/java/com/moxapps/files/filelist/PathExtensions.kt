package com.moxapps.files.filelist

import java8.nio.file.Path
import com.moxapps.files.file.MimeType
import com.moxapps.files.file.isSupportedArchive
import com.moxapps.files.provider.archive.archiveFile
import com.moxapps.files.provider.archive.isArchivePath
import com.moxapps.files.provider.document.isDocumentPath
import com.moxapps.files.provider.document.resolver.DocumentResolver
import com.moxapps.files.provider.linux.isLinuxPath

val Path.name: String
    get() = fileName?.toString() ?: if (isArchivePath) archiveFile.fileName.toString() else "/"

fun Path.toUserFriendlyString(): String = if (isLinuxPath) toFile().path else toUri().toString()

fun Path.isArchiveFile(mimeType: MimeType): Boolean = !isArchivePath && mimeType.isSupportedArchive

val Path.isLocalPath: Boolean
    get() =
        isLinuxPath || (isDocumentPath && DocumentResolver.isLocal(this as DocumentResolver.Path))

val Path.isRemotePath: Boolean
    get() = !isLocalPath
