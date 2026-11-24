package com.moxapps.files.provider.archive

import android.os.Parcel
import android.os.Parcelable
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.ClosedFileSystemException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import java8.nio.file.NotLinkException
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import com.moxapps.files.provider.archive.archiver.ArchiveReader
import com.moxapps.files.provider.archive.archiver.ReadArchive
import com.moxapps.files.provider.archive.zip.FastZipEditor
import com.moxapps.files.provider.archive.zip.FastZipFile
import com.moxapps.files.provider.archive.zip.StreamingEntrySeekableByteChannel
import com.moxapps.files.provider.common.ByteString
import com.moxapps.files.provider.common.ByteStringBuilder
import com.moxapps.files.provider.common.ByteStringListPathCreator
import com.moxapps.files.provider.common.IsDirectoryException
import com.moxapps.files.provider.common.LocalWatchService
import com.moxapps.files.provider.common.readAttributes
import com.moxapps.files.provider.common.toByteString
import com.moxapps.files.settings.Settings
import com.moxapps.files.util.valueCompat
import me.zhanghai.android.libarchive.ArchiveException
import java.io.IOException
import java.io.InputStream

internal class ArchiveFileSystem(
    private val provider: ArchiveFileSystemProvider,
    val archiveFile: Path
) : FileSystem(), ByteStringListPathCreator, Parcelable {
    val rootDirectory = ArchivePath(this, SEPARATOR_BYTE_STRING)

    init {
        if (!rootDirectory.isAbsolute) {
            throw AssertionError("Root directory $rootDirectory must be absolute")
        }
        if (rootDirectory.nameCount != 0) {
            throw AssertionError("Root directory $rootDirectory must contain no names")
        }
    }

    val defaultDirectory: ArchivePath
        get() = rootDirectory

    val isWritableZip: Boolean
        get() = !archiveFile.fileSystem.isReadOnly &&
            archiveFile.fileName?.toString()?.endsWith(".zip", ignoreCase = true) == true

    private val lock = Any()

    private var isOpen = true

    private var passwords = listOf<String>()

    private var isRefreshNeeded = true

    private var cachedLastModifiedMillis: Long = Long.MIN_VALUE
    private var cachedArchiveSize: Long = Long.MIN_VALUE

    private var entries: Map<Path, ReadArchive.Entry>? = null

    private var tree: Map<Path, List<Path>>? = null

    private var zipEntries: Map<Path, FastZipFile.ZipEntryInfo>? = null

    @Throws(IOException::class)
    fun getEntry(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            ensureEntriesLocked(path)
            getEntryLocked(path)
        }

    @Throws(IOException::class)
    fun getEntryOrNull(path: Path): ReadArchive.Entry? =
        synchronized(lock) {
            ensureEntriesLocked(path)
            entries!![path]
        }

    @Throws(IOException::class)
    private fun getEntryLocked(path: Path): ReadArchive.Entry =
        synchronized(lock) {
            entries!![path] ?: throw NoSuchFileException(path.toString())
        }

    @Throws(IOException::class)
    fun newInputStream(file: Path): InputStream =
        synchronized(lock) {
            ensureEntriesLocked(file)
            val entry = getEntryLocked(file)
            if (entry.isDirectory) {
                throw IsDirectoryException(file.toString())
            }
            val zipEntry = zipEntries?.get(file)
            val inputStream = try {
                ArchiveReader.newInputStream(archiveFile, passwords, entry, zipEntry)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            } ?: throw NoSuchFileException(file.toString())
            ArchiveExceptionInputStream(inputStream, file)
        }

    @Throws(IOException::class)
    fun newByteChannel(file: Path): SeekableByteChannel =
        synchronized(lock) {
            ensureEntriesLocked(file)
            val entry = getEntryLocked(file)
            if (entry.isDirectory) {
                throw IsDirectoryException(file.toString())
            }
            val zipEntry = zipEntries?.get(file)
            val currentPasswords = passwords.toList()
            if (zipEntry != null && zipEntry.isFastReadSupported &&
                !zipEntry.isEncrypted && zipEntry.actualMethod == 0
            ) {
                return@synchronized try {
                    ArchiveReader.newByteChannel(archiveFile, currentPasswords, entry, zipEntry)
                } catch (e: ArchiveException) {
                    throw e.toFileSystemOrInterruptedIOException(file)
                }
            }
            val openEntryStream: () -> InputStream = {
                val inputStream = try {
                    ArchiveReader.newInputStream(archiveFile, currentPasswords, entry, zipEntry)
                } catch (e: ArchiveException) {
                    throw e.toFileSystemOrInterruptedIOException(file)
                } ?: throw NoSuchFileException(file.toString())
                ArchiveExceptionInputStream(inputStream, file)
            }
            val initialStream = openEntryStream()
            StreamingEntrySeekableByteChannel(
                entrySize = entry.size,
                initialStream = initialStream,
                streamOpener = openEntryStream
            )
        }

    @Throws(IOException::class)
    fun readEntriesBatch(
        requestedPaths: Set<Path>,
        onEntry: (path: Path, entry: ReadArchive.Entry, openStream: () -> InputStream) -> Unit
    ) {
        val (currentPasswords, currentZipEntries) = synchronized(lock) {
            ensureEntriesLocked(rootDirectory)
            passwords.toList() to zipEntries
        }
        try {
            ArchiveReader.readEntriesBatch(
                archiveFile,
                currentPasswords,
                rootDirectory,
                currentZipEntries,
                requestedPaths
            ) { path, entry, openStream ->
                onEntry(path, entry) {
                    try {
                        ArchiveExceptionInputStream(openStream(), path)
                    } catch (e: ArchiveException) {
                        throw e.toFileSystemOrInterruptedIOException(path)
                    }
                }
            }
        } catch (e: ArchiveException) {
            throw e.toFileSystemOrInterruptedIOException(rootDirectory)
        }
    }

    @Throws(IOException::class)
    fun applyZipMutations(mutations: List<FastZipEditor.Mutation>) {
        val (charset, password) = synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            val hasEncrypted = entries?.values?.any { it.isEncrypted } == true
            val activePassword = if (hasEncrypted) passwords.lastOrNull() else null
            ArchiveReader.archiveFileNameCharset to activePassword
        }
        FastZipEditor.applyMutations(
            archiveFile = archiveFile,
            charset = charset,
            mutations = mutations,
            compressionLevel = Settings.CREATE_ARCHIVE_COMPRESSION_LEVEL.valueCompat,
            password = password
        )
        refresh()
    }

    @Throws(IOException::class)
    fun getDirectoryChildren(directory: Path): List<Path> =
        synchronized(lock) {
            ensureEntriesLocked(directory)
            val entry = getEntryLocked(directory)
            if (!entry.isDirectory) {
                throw NotDirectoryException(directory.toString())
            }
            tree!![directory]!!
        }

    @Throws(IOException::class)
    fun readSymbolicLink(link: Path): String =
        synchronized(lock) {
            ensureEntriesLocked(link)
            val entry = getEntryLocked(link)
            if (!entry.isSymbolicLink) {
                throw NotLinkException(link.toString())
            }
            entry.symbolicLinkTarget.orEmpty()
        }

    fun addPassword(password: String) {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            passwords += password
        }
    }

    fun refresh() {
        synchronized(lock) {
            if (!isOpen) {
                throw ClosedFileSystemException()
            }
            isRefreshNeeded = true
        }
    }

    @Throws(IOException::class)
    private fun ensureEntriesLocked(file: Path) {
        if (!isOpen) {
            throw ClosedFileSystemException()
        }
        val attrs = try {
            archiveFile.readAttributes(BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            null
        }
        if (attrs != null) {
            val mtime = attrs.lastModifiedTime().toMillis()
            val size = attrs.size()
            if (mtime != cachedLastModifiedMillis || size != cachedArchiveSize) {
                isRefreshNeeded = true
                cachedLastModifiedMillis = mtime
                cachedArchiveSize = size
            }
        }
        if (isRefreshNeeded) {
            val index = try {
                ArchiveReader.readEntries(archiveFile, passwords, rootDirectory)
            } catch (e: ArchiveException) {
                throw e.toFileSystemOrInterruptedIOException(file)
            }
            entries = index.entries
            tree = index.tree
            zipEntries = index.zipEntries
            isRefreshNeeded = false
        }
    }

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        synchronized(lock) {
            if (!isOpen) {
                return
            }
            provider.removeFileSystem(this)
            isRefreshNeeded = false
            entries = null
            tree = null
            zipEntries = null
            isOpen = false
        }
    }

    override fun isOpen(): Boolean = synchronized(lock) { isOpen }

    override fun isReadOnly(): Boolean = !isWritableZip

    override fun getSeparator(): String = SEPARATOR_STRING

    override fun getRootDirectories(): Iterable<Path> = listOf(rootDirectory)

    override fun getFileStores(): Iterable<FileStore> {
        // TODO
        throw UnsupportedOperationException()
    }

    override fun supportedFileAttributeViews(): Set<String> =
        ArchiveFileAttributeView.SUPPORTED_NAMES

    override fun getPath(first: String, vararg more: String): ArchivePath {
        val path = ByteStringBuilder(first.toByteString())
            .apply { more.forEach { append(SEPARATOR).append(it.toByteString()) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPath(first: ByteString, vararg more: ByteString): ArchivePath {
        val path = ByteStringBuilder(first)
            .apply { more.forEach { append(SEPARATOR).append(it) } }
            .toByteString()
        return ArchivePath(this, path)
    }

    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher {
        throw UnsupportedOperationException()
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newWatchService(): WatchService = LocalWatchService()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (javaClass != other?.javaClass) {
            return false
        }
        other as ArchiveFileSystem
        return archiveFile == other.archiveFile
    }

    override fun hashCode(): Int = archiveFile.hashCode()

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(archiveFile as Parcelable, flags)
    }

    companion object {
        const val SEPARATOR = '/'.code.toByte()
        private val SEPARATOR_BYTE_STRING = SEPARATOR.toByteString()
        private const val SEPARATOR_STRING = SEPARATOR.toInt().toChar().toString()

        @JvmField
        val CREATOR = object : Parcelable.Creator<ArchiveFileSystem> {
            override fun createFromParcel(source: Parcel): ArchiveFileSystem {
                val archiveFile = source.readParcelable<Parcelable>(Path::class.java.classLoader)
                    as Path
                return ArchiveFileSystemProvider.getOrNewFileSystem(archiveFile)
            }

            override fun newArray(size: Int): Array<ArchiveFileSystem?> = arrayOfNulls(size)
        }
    }
}
