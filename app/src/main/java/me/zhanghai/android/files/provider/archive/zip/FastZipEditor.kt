/*
 * Copyright (c) 2026 MaterialFilesExtended Contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.zip

import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.archive.archiver.ArchiveCompressionLevel
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.deleteIfExists
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.time.Instant

/**
 * Performs safe atomic in-place modifications on a ZIP archive by streaming untouched entries'
 * raw compressed bytes directly (without decompression/recompression) into a sibling temporary
 * archive and atomically replacing the original file.
 */
internal object FastZipEditor {
    sealed class Mutation {
        data class Delete(val entryPath: String, val recursive: Boolean = true) : Mutation()
        data class Rename(val fromPath: String, val toPath: String) : Mutation()
        data class MakeDirectory(
            val entryPath: String,
            val lastModifiedTime: FileTime? = FileTime.from(Instant.now()),
            val mode: Set<PosixFileModeBit> = PosixFileMode.DIRECTORY_DEFAULT
        ) : Mutation()
        data class PutStream(
            val entryPath: String,
            val lastModifiedTime: FileTime?,
            val mode: Set<PosixFileModeBit>,
            val sizeHint: Long,
            val openStream: () -> InputStream,
            val intervalMillis: Long = 500L,
            val progressListener: ((Long) -> Unit)? = null
        ) : Mutation()
        data class PutPath(
            val entryPath: String,
            val sourcePath: Path,
            val intervalMillis: Long = 500L,
            val progressListener: ((Long) -> Unit)? = null
        ) : Mutation()
    }

    @Throws(IOException::class)
    fun applyMutations(
        archiveFile: Path,
        charset: Charset,
        mutations: List<Mutation>,
        compressionLevel: ArchiveCompressionLevel = ArchiveCompressionLevel.NORMAL,
        password: String? = null
    ) {
        if (mutations.isEmpty()) return

        val deletePrefixes = mutableListOf<String>()
        val deleteExact = mutableSetOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        val dirsToAdd = linkedMapOf<String, Mutation.MakeDirectory>()
        val filesToPut = linkedMapOf<String, Mutation>()

        for (mutation in mutations) {
            when (mutation) {
                is Mutation.Delete -> {
                    val norm = normalizeEntryPath(mutation.entryPath)
                    if (norm.isNotEmpty()) {
                        deleteExact += norm
                        if (mutation.recursive) {
                            deletePrefixes += "$norm/"
                        }
                    }
                }
                is Mutation.Rename -> {
                    val from = normalizeEntryPath(mutation.fromPath)
                    val to = normalizeEntryPath(mutation.toPath)
                    if (from.isNotEmpty() && to.isNotEmpty() && from != to) {
                        renames += from to to
                    }
                }
                is Mutation.MakeDirectory -> {
                    val norm = normalizeEntryPath(mutation.entryPath)
                    if (norm.isNotEmpty()) {
                        deleteExact += norm
                        dirsToAdd[norm] = mutation
                    }
                }
                is Mutation.PutStream -> {
                    val norm = normalizeEntryPath(mutation.entryPath)
                    if (norm.isNotEmpty()) {
                        deleteExact += norm
                        filesToPut[norm] = mutation
                    }
                }
                is Mutation.PutPath -> {
                    val norm = normalizeEntryPath(mutation.entryPath)
                    if (norm.isNotEmpty()) {
                        deleteExact += norm
                        filesToPut[norm] = mutation
                    }
                }
            }
        }

        val tempFile = createSiblingTempPath(archiveFile)
        var successful = false
        try {
            archiveFile.newByteChannel().use { sourceChannel ->
                val existingEntries = FastZipFile.readEntries(sourceChannel, charset)
                val destChannel = tempFile.newByteChannel(
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                )
                FastZipWriter(destChannel, compressionLevel, password).use { writer ->
                    val writtenNames = mutableSetOf<String>()

                    for (entry in existingEntries) {
                        val entryName = normalizeEntryPath(entry.name)
                        if (entryName.isEmpty()) continue
                        if (isDeleted(entryName, deleteExact, deletePrefixes)) {
                            continue
                        }
                        val targetName = applyRename(entryName, renames)
                        // If a rename overwrites something or a put replaces it, skip old entry if overwritten.
                        if (targetName in filesToPut || targetName in dirsToAdd) {
                            continue
                        }
                        if (!writtenNames.add(targetName)) {
                            continue
                        }
                        val zipEntryName = if (entry.isDirectory) "$targetName/" else targetName
                        writer.copyRawEntry(sourceChannel, entry, zipEntryName)
                    }

                    for ((dirPath, dirMut) in dirsToAdd) {
                        if (writtenNames.add(dirPath)) {
                            writer.writeDirectory(dirPath, dirMut.lastModifiedTime, dirMut.mode)
                        }
                    }

                    for ((filePath, putMut) in filesToPut) {
                        writtenNames.add(filePath)
                        when (putMut) {
                            is Mutation.PutStream -> {
                                putMut.openStream().use { input ->
                                    writer.writeFile(
                                        entryName = filePath,
                                        lastModifiedTime = putMut.lastModifiedTime,
                                        mode = putMut.mode,
                                        uncompressedSizeHint = putMut.sizeHint,
                                        inputStream = input,
                                        intervalMillis = putMut.intervalMillis,
                                        listener = putMut.progressListener
                                    )
                                }
                            }
                            is Mutation.PutPath -> {
                                writePathToZip(
                                    writer = writer,
                                    entryPath = filePath,
                                    sourcePath = putMut.sourcePath,
                                    intervalMillis = putMut.intervalMillis,
                                    progressListener = putMut.progressListener
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
            tempFile.moveTo(archiveFile, StandardCopyOption.REPLACE_EXISTING)
            successful = true
        } finally {
            if (!successful) {
                try {
                    tempFile.deleteIfExists()
                } catch (_: IOException) {
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun writePathToZip(
        writer: FastZipWriter,
        entryPath: String,
        sourcePath: Path,
        intervalMillis: Long,
        progressListener: ((Long) -> Unit)?
    ) {
        val attributes = sourcePath.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val posix = attributes as? PosixFileAttributes
        val mtime = attributes.lastModifiedTime()
        when {
            attributes.isDirectory -> {
                val mode = posix?.mode() ?: PosixFileMode.DIRECTORY_DEFAULT
                writer.writeDirectory(entryPath, mtime, mode)
                progressListener?.invoke(attributes.size())
            }
            attributes.isSymbolicLink -> {
                val mode = posix?.mode() ?: PosixFileMode.SYMBOLIC_LINK_DEFAULT
                val target = sourcePath.readSymbolicLinkByteString().toString()
                writer.writeSymbolicLink(entryPath, target, mtime, mode)
                progressListener?.invoke(attributes.size())
            }
            else -> {
                val mode = posix?.mode() ?: PosixFileMode.FILE_DEFAULT
                sourcePath.newInputStream(LinkOption.NOFOLLOW_LINKS).use { input ->
                    writer.writeFile(
                        entryName = entryPath,
                        lastModifiedTime = mtime,
                        mode = mode,
                        uncompressedSizeHint = attributes.size(),
                        inputStream = input,
                        intervalMillis = intervalMillis,
                        listener = progressListener
                    )
                }
            }
        }
    }

    private fun isDeleted(
        entryName: String,
        deleteExact: Set<String>,
        deletePrefixes: List<String>
    ): Boolean {
        if (entryName in deleteExact) return true
        for (prefix in deletePrefixes) {
            if (entryName.startsWith(prefix)) return true
        }
        return false
    }

    private fun applyRename(entryName: String, renames: List<Pair<String, String>>): String {
        var current = entryName
        for ((from, to) in renames) {
            if (current == from) {
                current = to
            } else if (current.startsWith("$from/")) {
                current = to + current.substring(from.length)
            }
        }
        return current
    }

    private fun normalizeEntryPath(path: String): String =
        path.replace('\\', '/').trim('/')

    private fun createSiblingTempPath(archiveFile: Path): Path {
        val baseName = archiveFile.fileName?.toString() ?: "archive.zip"
        return archiveFile.resolveSibling(".$baseName.tmp.${System.nanoTime()}")
    }
}
