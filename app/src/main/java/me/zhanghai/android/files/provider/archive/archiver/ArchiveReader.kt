/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import androidx.preference.PreferenceManager
import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.archive.zip.FastZipFile
import me.zhanghai.android.files.provider.archive.zip.SliceSeekableByteChannel
import me.zhanghai.android.files.provider.archive.zip.StreamingEntrySeekableByteChannel
import me.zhanghai.android.files.provider.common.DelegateForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.DelegateInputStream
import me.zhanghai.android.files.provider.common.DelegateNonForceableSeekableByteChannel
import me.zhanghai.android.files.provider.common.ForceableChannel
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import me.zhanghai.android.files.provider.root.rootContext
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

object ArchiveReader {
    class ArchiveIndex(
        val entries: Map<Path, ReadArchive.Entry>,
        val tree: Map<Path, List<Path>>,
        val zipEntries: Map<Path, FastZipFile.ZipEntryInfo>?
    )

    @Throws(IOException::class)
    fun readEntries(
        file: Path,
        passwords: List<String>,
        rootPath: Path
    ): ArchiveIndex {
        val entries = mutableMapOf<Path, ReadArchive.Entry>()
        var zipEntriesMap: MutableMap<Path, FastZipFile.ZipEntryInfo>? = null

        val fastZipList = tryReadZipEntries(file)
        if (fastZipList != null) {
            zipEntriesMap = mutableMapOf()
            for (zipEntry in fastZipList) {
                val path = resolveNormalizedPath(rootPath, zipEntry.name, zipEntry.isDirectory)
                    ?: continue
                val readEntry = zipEntry.toReadArchiveEntry()
                if (!entries.containsKey(path)) {
                    entries[path] = readEntry
                    zipEntriesMap[path] = zipEntry
                }
            }
        } else {
            val rawEntries = readEntries(file, passwords)
            for (entry in rawEntries) {
                val path = resolveNormalizedPath(rootPath, entry.name, entry.isDirectory)
                    ?: continue
                entries.getOrPut(path) { entry }
            }
        }

        entries.getOrPut(rootPath) { createDirectoryEntry("") }
        val tree = mutableMapOf<Path, MutableList<Path>>()
        tree[rootPath] = mutableListOf()
        val paths = entries.keys.toList()
        for (path in paths) {
            var currentPath = path
            while (true) {
                val parentPath = currentPath.parent ?: break
                val entry = entries[currentPath]!!
                if (entry.isDirectory) {
                    tree.getOrPut(currentPath) { mutableListOf() }
                }
                tree.getOrPut(parentPath) { mutableListOf() }.add(currentPath)
                if (entries.containsKey(parentPath)) {
                    break
                }
                entries[parentPath] = createDirectoryEntry(parentPath.toString())
                currentPath = parentPath
            }
        }
        return ArchiveIndex(entries, tree, zipEntriesMap)
    }

    private fun resolveNormalizedPath(
        rootPath: Path,
        entryName: String,
        isDirectory: Boolean
    ): Path? {
        var path = rootPath.resolve(entryName)
        if (!path.isAbsolute) {
            throw AssertionError("Path must be absolute: $path")
        }
        if (path.nameCount > 0) {
            path = path.normalize()
            if (path.nameCount == 0) {
                // Don't allow a path to become the root path only after normalization.
                return null
            }
        } else if (!isDirectory) {
            return null
        }
        return path
    }

    private fun tryReadZipEntries(file: Path): List<FastZipFile.ZipEntryInfo>? {
        val channel = try {
            CacheSizeSeekableByteChannel(file.newByteChannel())
        } catch (_: Exception) {
            return null
        }
        return channel.use {
            try {
                if (!FastZipFile.isZipChannel(it)) {
                    return null
                }
                FastZipFile.readEntries(it, archiveFileNameCharset)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun createDirectoryEntry(name: String): ReadArchive.Entry {
        require(!name.endsWith("/")) { "name $name should not end with a slash" }
        return ReadArchive.Entry(
            name, false, null, null, null, PosixFileType.DIRECTORY, 0, null, null,
            PosixFileMode.DIRECTORY_DEFAULT, null
        )
    }

    @Throws(IOException::class)
    private fun readEntries(file: Path, passwords: List<String>): List<ReadArchive.Entry> {
        val charset = archiveFileNameCharset
        val (archive, closeable) = openArchive(file, passwords)
        return closeable.use {
            buildList {
                while (true) {
                    this += archive.readEntry(charset) ?: break
                }
            }
        }
    }

    @Throws(IOException::class)
    fun newInputStream(
        file: Path,
        passwords: List<String>,
        entry: ReadArchive.Entry,
        zipEntry: FastZipFile.ZipEntryInfo? = null
    ): InputStream? {
        if (zipEntry != null && zipEntry.isFastReadSupported) {
            val channel = CacheSizeSeekableByteChannel(file.newByteChannel())
            return FastZipFile.openEntryInputStream(
                channel, zipEntry, passwords, closeChannelOnClose = true, archiveFileNameCharset
            )
        }
        val charset = archiveFileNameCharset
        val (archive, closeable) = openArchive(file, passwords)
        var successful = false
        return try {
            while (true) {
                val currentEntry = archive.readEntry(charset) ?: break
                if (currentEntry.name != entry.name) {
                    continue
                }
                successful = true
                break
            }
            if (successful) {
                CloseableInputStream(archive.newDataInputStream(), closeable)
            } else {
                null
            }
        } finally {
            if (!successful) {
                closeable.close()
            }
        }
    }

    @Throws(IOException::class)
    fun newByteChannel(
        file: Path,
        passwords: List<String>,
        entry: ReadArchive.Entry,
        zipEntry: FastZipFile.ZipEntryInfo? = null
    ): SeekableByteChannel {
        if (zipEntry != null && zipEntry.isFastReadSupported &&
            !zipEntry.isEncrypted && zipEntry.actualMethod == 0
        ) {
            val channel = CacheSizeSeekableByteChannel(file.newByteChannel())
            var successful = false
            return try {
                val (dataOffset, _) = FastZipFile.getEntryDataRange(channel, zipEntry)
                val slice = SliceSeekableByteChannel(channel, dataOffset, zipEntry.uncompressedSize)
                successful = true
                slice
            } finally {
                if (!successful) {
                    channel.close()
                }
            }
        }
        return StreamingEntrySeekableByteChannel(entry.size) {
            newInputStream(file, passwords, entry, zipEntry)
                ?: throw IOException("Archive entry not found: ${entry.name}")
        }
    }

    /**
     * Reads multiple entries from an archive in a single pass (O(1) per entry for ZIP, single
     * sequential pass for solid 7z/tar/rar), avoiding O(N^2) archive re-opening.
     */
    @Throws(IOException::class)
    fun readEntriesBatch(
        file: Path,
        passwords: List<String>,
        rootPath: Path,
        zipEntries: Map<Path, FastZipFile.ZipEntryInfo>?,
        requestedPaths: Set<Path>,
        onEntry: (path: Path, entry: ReadArchive.Entry, openStream: () -> InputStream) -> Unit
    ) {
        if (requestedPaths.isEmpty()) return

        val remainingForLibArchive = mutableSetOf<Path>()
        if (zipEntries != null) {
            val fastZipPaths = requestedPaths.filter {
                val ze = zipEntries[it]
                ze != null && ze.isFastReadSupported
            }
            if (fastZipPaths.isNotEmpty()) {
                val charset = archiveFileNameCharset
                CacheSizeSeekableByteChannel(file.newByteChannel()).use { channel ->
                    for (path in fastZipPaths) {
                        val ze = zipEntries[path]!!
                        onEntry(path, ze.toReadArchiveEntry()) {
                            FastZipFile.openEntryInputStream(
                                channel, ze, passwords, closeChannelOnClose = false, charset
                            )
                        }
                    }
                }
            }
            for (path in requestedPaths) {
                val ze = zipEntries[path]
                if (ze == null || !ze.isFastReadSupported) {
                    remainingForLibArchive += path
                }
            }
        } else {
            remainingForLibArchive += requestedPaths
        }

        if (remainingForLibArchive.isEmpty()) return

        val charset = archiveFileNameCharset
        val (archive, closeable) = openArchive(file, passwords)
        val remaining = remainingForLibArchive.toMutableSet()
        closeable.use {
            while (remaining.isNotEmpty()) {
                val currentEntry = archive.readEntry(charset) ?: break
                val normalized = resolveNormalizedPath(
                    rootPath, currentEntry.name, currentEntry.isDirectory
                ) ?: continue
                if (remaining.remove(normalized)) {
                    onEntry(normalized, currentEntry) {
                        NonClosingInputStream(archive.newDataInputStream())
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun openArchive(
        file: Path,
        passwords: List<String>
    ): Pair<ReadArchive, ArchiveCloseable> {
        val channel = try {
            CacheSizeSeekableByteChannel(file.newByteChannel())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (channel != null) {
            var successful = false
            try {
                val archive = ReadArchive(channel, passwords)
                successful = true
                return archive to ArchiveCloseable(archive, channel)
            } finally {
                if (!successful) {
                    channel.close()
                }
            }
        }
        val inputStream = file.newInputStream()
        var successful = false
        try {
            val archive = ReadArchive(inputStream, passwords)
            successful = true
            return archive to ArchiveCloseable(archive, inputStream)
        } finally {
            if (!successful) {
                inputStream.close()
            }
        }
    }

    // size() may be called repeatedly for ZIP and 7Z, so make it cached to improve performance.
    private fun CacheSizeSeekableByteChannel(channel: SeekableByteChannel): SeekableByteChannel =
        if (channel is ForceableChannel) {
            CacheSizeForceableSeekableByteChannel(channel)
        } else {
            CacheSizeNonForceableSeekableByteChannel(channel)
        }

    private class CacheSizeNonForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateNonForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    private class CacheSizeForceableSeekableByteChannel(
        channel: SeekableByteChannel
    ) : DelegateForceableSeekableByteChannel(channel) {
        private val size: Long by lazy { super.size() }

        override fun size(): Long = size
    }

    val archiveFileNameCharset: Charset
        get() =
            if (isRunningAsRoot) {
                try {
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(rootContext)
                    val key = rootContext.getString(R.string.pref_key_archive_file_name_encoding)
                    val defaultValue = rootContext.getString(
                        R.string.pref_default_value_archive_file_name_encoding
                    )
                    Charset.forName(sharedPreferences.getString(key, defaultValue)!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                    StandardCharsets.UTF_8
                }
            } else {
                Charset.forName(Settings.ARCHIVE_FILE_NAME_ENCODING.valueCompat)
            }

    private class ArchiveCloseable(
        private val archive: ReadArchive,
        private val closeable: Closeable
    ) : Closeable {
        override fun close() {
            @Suppress("ConvertTryFinallyToUseCall")
            try {
                archive.close()
            } finally {
                closeable.close()
            }
        }
    }

    private class CloseableInputStream(
        inputStream: InputStream,
        private val closeable: Closeable
    ) : DelegateInputStream(inputStream) {
        @Throws(IOException::class)
        override fun close() {
            super.close()

            closeable.close()
        }
    }

    private class NonClosingInputStream(
        inputStream: InputStream
    ) : DelegateInputStream(inputStream) {
        override fun close() {
            // Keep underlying ReadArchive stream open for subsequent entries in the batch.
        }
    }
}
