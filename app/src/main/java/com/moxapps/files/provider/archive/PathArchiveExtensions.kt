package com.moxapps.files.provider.archive

import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import com.moxapps.files.provider.archive.archiver.ReadArchive
import com.moxapps.files.provider.archive.zip.FastZipEditor
import java.io.IOException
import java.io.InputStream

fun Path.archiveAddPassword(password: String) {
    this as? ArchivePath ?: throw ProviderMismatchException(toString())
    fileSystem.addPassword(password)
}

val Path.archiveFile: Path
    get() {
        this as? ArchivePath ?: throw ProviderMismatchException(toString())
        return fileSystem.archiveFile
    }

fun Path.archiveRefresh() {
    this as? ArchivePath ?: throw ProviderMismatchException(toString())
    fileSystem.refresh()
}

fun Path.createArchiveRootPath(): Path =
    ArchiveFileSystemProvider.getOrNewFileSystem(this).rootDirectory

val Path.isWritableZipArchive: Boolean
    get() = this is ArchivePath && fileSystem.isWritableZip

@Throws(IOException::class)
internal fun Path.archiveReadEntriesBatch(
    requestedPaths: Set<Path>,
    onEntry: (path: Path, entry: ReadArchive.Entry, openStream: () -> InputStream) -> Unit
) {
    this as? ArchivePath ?: throw ProviderMismatchException(toString())
    fileSystem.readEntriesBatch(requestedPaths, onEntry)
}

@Throws(IOException::class)
internal fun Path.archiveApplyZipMutations(mutations: List<FastZipEditor.Mutation>) {
    this as? ArchivePath ?: throw ProviderMismatchException(toString())
    fileSystem.applyZipMutations(mutations)
}

internal fun Path.toZipEntryPath(): String {
    this as? ArchivePath ?: throw ProviderMismatchException(toString())
    return ArchiveFileSystemProvider.toEntryPath(this)
}
