/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive

import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.DirectoryStream
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.ProviderMismatchException
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.archive.zip.FastZipEditor
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.FileSystemCache
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.Searchable
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.WatchServicePathObservable
import me.zhanghai.android.files.provider.common.decodedPathByteString
import me.zhanghai.android.files.provider.common.decodedQueryByteString
import me.zhanghai.android.files.provider.common.isSameFile
import me.zhanghai.android.files.provider.common.toAccessModes
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toCopyOptions
import me.zhanghai.android.files.provider.common.toOpenOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.time.Instant
import kotlin.math.min

object ArchiveFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    private const val SCHEME = "archive"

    private val fileSystems = FileSystemCache<Path, ArchiveFileSystem>()

    override fun getScheme(): String = SCHEME

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        return fileSystems.create(archiveFile) { newFileSystem(archiveFile) }
    }

    override fun newFileSystem(file: Path, env: Map<String, *>): FileSystem = newFileSystem(file)

    internal fun getOrNewFileSystem(archiveFile: Path): ArchiveFileSystem =
        fileSystems.getOrCreate(archiveFile) { newFileSystem(archiveFile) }

    private fun newFileSystem(archiveFile: Path): ArchiveFileSystem =
        ArchiveFileSystem(this, archiveFile)

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        return fileSystems[archiveFile]
    }

    internal fun removeFileSystem(fileSystem: ArchiveFileSystem) {
        fileSystems.remove(fileSystem.archiveFile, fileSystem)
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val archiveFile = uri.archiveFile
        val path = uri.decodedQueryByteString
            ?: throw IllegalArgumentException("URI must have a query")
        return getOrNewFileSystem(archiveFile).getPath(path)
    }

    private fun URI.requireSameScheme() {
        val scheme = scheme
        require(scheme == SCHEME) { "URI scheme $scheme must be $SCHEME" }
    }

    private val URI.archiveFile: Path
        get() {
            val path = decodedPathByteString
                ?: throw IllegalArgumentException("URI must have a path")
            // Drop the first character which is always a slash.
            val archiveUri = URI.create(path.toString().drop(1))
            return Paths.get(archiveUri)
        }

    @Throws(IOException::class)
    override fun newInputStream(file: Path, vararg options: OpenOption): InputStream {
        file as? ArchivePath ?: throw ProviderMismatchException(file.toString())
        options.toOpenOptions().checkForArchive(isWritable = false)
        return file.fileSystem.newInputStream(file)
    }

    @Throws(IOException::class)
    override fun newOutputStream(file: Path, vararg options: OpenOption): OutputStream {
        file as? ArchivePath ?: throw ProviderMismatchException(file.toString())
        val isWritable = file.fileSystem.isWritableZip
        if (!isWritable) {
            throw ReadOnlyFileSystemException(file.toString())
        }
        val optionsSet = mutableSetOf(*options)
        if (optionsSet.isEmpty()) {
            optionsSet += StandardOpenOption.CREATE
            optionsSet += StandardOpenOption.TRUNCATE_EXISTING
        }
        optionsSet += StandardOpenOption.WRITE
        val openOptions = optionsSet.toOpenOptions()
        openOptions.checkForArchive(isWritable = true)
        if (!openOptions.truncateExisting && !openOptions.createNew) {
            throw UnsupportedOperationException("Missing ${StandardOpenOption.TRUNCATE_EXISTING}")
        }
        val existingEntry = file.fileSystem.getEntryOrNull(file)
        if (existingEntry != null) {
            if (existingEntry.isDirectory) {
                throw IsDirectoryException(file.toString())
            }
            if (openOptions.createNew) {
                throw FileAlreadyExistsException(file.toString())
            }
        } else if (!(openOptions.create || openOptions.createNew)) {
            throw NoSuchFileException(file.toString())
        }
        if (file == file.fileSystem.rootDirectory) {
            throw IsDirectoryException(file.toString())
        }
        val parent = file.parent
        if (parent != null && file.fileSystem.getEntryOrNull(parent)?.isDirectory != true) {
            throw NoSuchFileException(parent.toString())
        }
        return ZipEntryWriteOutputStream(file, isNew = existingEntry == null)
    }

    override fun newFileChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel {
        file as? ArchivePath ?: throw ProviderMismatchException(file.toString())
        options.toOpenOptions().checkForArchive(isWritable = file.fileSystem.isWritableZip)
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel {
        file as? ArchivePath ?: throw ProviderMismatchException(file.toString())
        val openOptions = options.toOpenOptions()
        val isWriteMode = openOptions.write || openOptions.create || openOptions.createNew
        if (isWriteMode && !file.fileSystem.isWritableZip) {
            throw ReadOnlyFileSystemException(file.toString())
        }
        openOptions.checkForArchive(isWritable = file.fileSystem.isWritableZip)
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        if (!isWriteMode) {
            return file.fileSystem.newByteChannel(file)
        }
        val existingEntry = file.fileSystem.getEntryOrNull(file)
        if (existingEntry != null) {
            if (existingEntry.isDirectory) {
                throw IsDirectoryException(file.toString())
            }
            if (openOptions.createNew) {
                throw FileAlreadyExistsException(file.toString())
            }
        } else if (!(openOptions.create || openOptions.createNew)) {
            throw NoSuchFileException(file.toString())
        }
        if (file == file.fileSystem.rootDirectory) {
            throw IsDirectoryException(file.toString())
        }
        val initialBytes = if (existingEntry != null && !openOptions.truncateExisting) {
            file.fileSystem.newInputStream(file).use { it.readBytes() }
        } else {
            null
        }
        return ZipEntryWriteSeekableByteChannel(
            file, isNew = existingEntry == null, initialBytes = initialBytes
        )
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        directory as? ArchivePath ?: throw ProviderMismatchException(directory.toString())
        val children = directory.fileSystem.getDirectoryChildren(directory)
        return PathListDirectoryStream(children, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        directory as? ArchivePath ?: throw ProviderMismatchException(directory.toString())
        if (!directory.fileSystem.isWritableZip) {
            throw ReadOnlyFileSystemException(directory.toString())
        }
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        val fs = directory.fileSystem
        if (directory == fs.rootDirectory || fs.getEntryOrNull(directory) != null) {
            throw FileAlreadyExistsException(directory.toString())
        }
        val parent = directory.parent
        if (parent != null && fs.getEntryOrNull(parent)?.isDirectory != true) {
            throw NoSuchFileException(parent.toString())
        }
        fs.applyZipMutations(
            listOf(FastZipEditor.Mutation.MakeDirectory(toEntryPath(directory)))
        )
        LocalWatchService.onEntryCreated(directory)
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        link as? ArchivePath ?: throw ProviderMismatchException(link.toString())
        when (target) {
            is ArchivePath, is ByteStringPath -> {}
            else -> throw ProviderMismatchException(target.toString())
        }
        throw ReadOnlyFileSystemException(link.toString(), target.toString(), null)
    }

    @Throws(IOException::class)
    override fun createLink(link: Path, existing: Path) {
        link as? ArchivePath ?: throw ProviderMismatchException(link.toString())
        existing as? ArchivePath ?: throw ProviderMismatchException(existing.toString())
        throw ReadOnlyFileSystemException(link.toString(), existing.toString(), null)
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        val fs = path.fileSystem
        if (!fs.isWritableZip) {
            throw ReadOnlyFileSystemException(path.toString())
        }
        if (path == fs.rootDirectory) {
            throw AccessDeniedException(path.toString(), null, "Cannot delete archive root")
        }
        val entry = fs.getEntry(path)
        if (entry.isDirectory && fs.getDirectoryChildren(path).isNotEmpty()) {
            throw DirectoryNotEmptyException(path.toString())
        }
        fs.applyZipMutations(
            listOf(FastZipEditor.Mutation.Delete(toEntryPath(path), recursive = false))
        )
        LocalWatchService.onEntryDeleted(path)
    }

    @Throws(IOException::class)
    override fun readSymbolicLink(link: Path): Path {
        link as? ArchivePath ?: throw ProviderMismatchException(link.toString())
        val target = link.fileSystem.readSymbolicLink(link)
        return ByteStringPath(target.toByteString())
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        source as? ArchivePath ?: throw ProviderMismatchException(source.toString())
        target as? ArchivePath ?: throw ProviderMismatchException(target.toString())
        val targetFs = target.fileSystem
        if (!targetFs.isWritableZip) {
            throw ReadOnlyFileSystemException(source.toString(), target.toString(), null)
        }
        val copyOptions = options.toCopyOptions()
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceFs = source.fileSystem
        val sourceEntry = sourceFs.getEntry(source)
        if (source == target) {
            copyOptions.progressListener?.invoke(sourceEntry.size)
            return
        }
        val targetEntry = targetFs.getEntryOrNull(target)
        if (targetEntry != null) {
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            if (targetEntry.isDirectory && targetFs.getDirectoryChildren(target).isNotEmpty()) {
                throw DirectoryNotEmptyException(target.toString())
            }
        }
        val targetEntryName = toEntryPath(target)
        val mutation = if (sourceEntry.isDirectory) {
            FastZipEditor.Mutation.MakeDirectory(
                targetEntryName,
                sourceEntry.lastModifiedTime,
                sourceEntry.mode
            )
        } else {
            FastZipEditor.Mutation.PutStream(
                entryPath = targetEntryName,
                lastModifiedTime = sourceEntry.lastModifiedTime,
                mode = sourceEntry.mode,
                sizeHint = sourceEntry.size,
                openStream = { sourceFs.newInputStream(source) },
                intervalMillis = copyOptions.progressIntervalMillis,
                progressListener = copyOptions.progressListener
            )
        }
        targetFs.applyZipMutations(listOf(mutation))
        if (targetEntry != null) {
            LocalWatchService.onEntryModified(target)
        } else {
            LocalWatchService.onEntryCreated(target)
        }
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? ArchivePath ?: throw ProviderMismatchException(source.toString())
        target as? ArchivePath ?: throw ProviderMismatchException(target.toString())
        val sourceFs = source.fileSystem
        val targetFs = target.fileSystem
        if (!sourceFs.isWritableZip || !targetFs.isWritableZip) {
            throw ReadOnlyFileSystemException(source.toString(), target.toString(), null)
        }
        val copyOptions = options.toCopyOptions()
        if (sourceFs != targetFs) {
            if (copyOptions.atomicMove) {
                throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
            }
            copy(source, target, *options)
            delete(source)
            return
        }
        if (source == sourceFs.rootDirectory || target == targetFs.rootDirectory) {
            throw AccessDeniedException(source.toString(), target.toString(), null)
        }
        val sourceEntry = sourceFs.getEntry(source)
        if (source == target) {
            copyOptions.progressListener?.invoke(sourceEntry.size)
            return
        }
        val targetEntry = targetFs.getEntryOrNull(target)
        val mutations = mutableListOf<FastZipEditor.Mutation>()
        if (targetEntry != null) {
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            if (targetEntry.isDirectory && targetFs.getDirectoryChildren(target).isNotEmpty()) {
                throw DirectoryNotEmptyException(target.toString())
            }
            mutations += FastZipEditor.Mutation.Delete(toEntryPath(target), recursive = false)
        }
        mutations += FastZipEditor.Mutation.Rename(toEntryPath(source), toEntryPath(target))
        sourceFs.applyZipMutations(mutations)
        copyOptions.progressListener?.invoke(sourceEntry.size)
        LocalWatchService.onEntryDeleted(source)
        LocalWatchService.onEntryCreated(target)
    }

    @Throws(IOException::class)
    override fun isSameFile(path: Path, path2: Path): Boolean {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        if (path == path2) {
            return true
        }
        if (path2 !is ArchivePath) {
            return false
        }
        val fileSystem = path.fileSystem
        if (!fileSystem.archiveFile.isSameFile(path2.fileSystem.archiveFile)) {
            return false
        }
        return path == fileSystem.getPath(path2.toString())
    }

    override fun isHidden(path: Path): Boolean {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        return false
    }

    override fun getFileStore(path: Path): FileStore {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        val archiveFile = path.fileSystem.archiveFile
        return ArchiveFileStore(archiveFile)
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        val accessModes = modes.toAccessModes()
        path.fileSystem.getEntry(path)
        if ((accessModes.write && !path.fileSystem.isWritableZip) || accessModes.execute) {
            throw AccessDeniedException(path.toString())
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        if (!supportsFileAttributeView(type)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path) as V
    }

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type.isAssignableFrom(ArchiveFileAttributeView::class.java)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        if (!type.isAssignableFrom(ArchiveFileAttributes::class.java)) {
            throw UnsupportedOperationException(type.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path).readAttributes() as A
    }

    private fun getFileAttributeView(path: ArchivePath): ArchiveFileAttributeView =
        ArchiveFileAttributeView(path)

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        path as? ArchivePath ?: throw ProviderMismatchException(path.toString())
        return WatchServicePathObservable(path, intervalMillis)
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        directory as? ArchivePath ?: throw ProviderMismatchException(directory.toString())
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }

    internal fun toEntryPath(path: ArchivePath): String =
        path.toString().trimStart('/')

    private class ZipEntryWriteOutputStream(
        private val target: ArchivePath,
        private val isNew: Boolean
    ) : OutputStream() {
        private var memBuf: ByteArrayOutputStream? = ByteArrayOutputStream()
        private var tempFile: File? = null
        private var tempOut: OutputStream? = null
        private var size = 0L
        private var closed = false

        @Throws(IOException::class)
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val mem = memBuf
            if (mem != null) {
                if (mem.size() + len <= IN_MEMORY_THRESHOLD) {
                    mem.write(b, off, len)
                    size += len
                    return
                }
                val file = File.createTempFile("mf_zip_entry_", ".tmp")
                val out = file.outputStream()
                out.write(mem.toByteArray())
                memBuf = null
                tempFile = file
                tempOut = out
            }
            tempOut!!.write(b, off, len)
            size += len
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) return
            closed = true
            try {
                tempOut?.close()
                val bytes = memBuf?.toByteArray()
                val file = tempFile
                target.fileSystem.applyZipMutations(
                    listOf(
                        FastZipEditor.Mutation.PutStream(
                            entryPath = toEntryPath(target),
                            lastModifiedTime = FileTime.from(Instant.now()),
                            mode = PosixFileMode.FILE_DEFAULT,
                            sizeHint = size,
                            openStream = {
                                if (bytes != null) {
                                    ByteArrayInputStream(bytes)
                                } else {
                                    FileInputStream(file!!)
                                }
                            }
                        )
                    )
                )
                if (isNew) {
                    LocalWatchService.onEntryCreated(target)
                } else {
                    LocalWatchService.onEntryModified(target)
                }
            } finally {
                tempFile?.delete()
                tempFile = null
                tempOut = null
                memBuf = null
            }
        }

        companion object {
            private const val IN_MEMORY_THRESHOLD = 4 * 1024 * 1024
        }
    }

    private class ZipEntryWriteSeekableByteChannel(
        private val target: ArchivePath,
        private val isNew: Boolean,
        initialBytes: ByteArray?
    ) : SeekableByteChannel {
        private val lock = Any()
        private val tempFile = File.createTempFile("mf_zip_ch_", ".tmp")
        private val raf = RandomAccessFile(tempFile, "rw")
        private var open = true

        init {
            if (initialBytes != null && initialBytes.isNotEmpty()) {
                raf.write(initialBytes)
                raf.seek(0)
            }
        }

        override fun isOpen(): Boolean = synchronized(lock) { open }

        @Throws(IOException::class)
        override fun position(): Long = synchronized(lock) {
            ensureOpen()
            raf.filePointer
        }

        @Throws(IOException::class)
        override fun position(newPosition: Long): SeekableByteChannel = synchronized(lock) {
            ensureOpen()
            raf.seek(newPosition)
            this
        }

        @Throws(IOException::class)
        override fun size(): Long = synchronized(lock) {
            ensureOpen()
            raf.length()
        }

        @Throws(IOException::class)
        override fun truncate(size: Long): SeekableByteChannel = synchronized(lock) {
            ensureOpen()
            raf.setLength(size)
            if (raf.filePointer > size) {
                raf.seek(size)
            }
            this
        }

        @Throws(IOException::class)
        override fun read(dst: ByteBuffer): Int = synchronized(lock) {
            ensureOpen()
            val len = min(dst.remaining(), 64 * 1024)
            if (len == 0) return 0
            val buf = ByteArray(len)
            val n = raf.read(buf)
            if (n <= 0) return -1
            dst.put(buf, 0, n)
            n
        }

        @Throws(IOException::class)
        override fun write(src: ByteBuffer): Int = synchronized(lock) {
            ensureOpen()
            val total = src.remaining()
            if (total == 0) return 0
            val buf = ByteArray(min(total, 64 * 1024))
            var written = 0
            while (src.hasRemaining()) {
                val chunk = min(src.remaining(), buf.size)
                src.get(buf, 0, chunk)
                raf.write(buf, 0, chunk)
                written += chunk
            }
            written
        }

        @Throws(IOException::class)
        override fun close() {
            synchronized(lock) {
                if (!open) return
                open = false
                try {
                    val finalSize = raf.length()
                    raf.close()
                    target.fileSystem.applyZipMutations(
                        listOf(
                            FastZipEditor.Mutation.PutStream(
                                entryPath = toEntryPath(target),
                                lastModifiedTime = FileTime.from(Instant.now()),
                                mode = PosixFileMode.FILE_DEFAULT,
                                sizeHint = finalSize,
                                openStream = { FileInputStream(tempFile) }
                            )
                        )
                    )
                    if (isNew) {
                        LocalWatchService.onEntryCreated(target)
                    } else {
                        LocalWatchService.onEntryModified(target)
                    }
                } finally {
                    tempFile.delete()
                }
            }
        }

        private fun ensureOpen() {
            if (!open) throw ClosedChannelException()
        }
    }
}
