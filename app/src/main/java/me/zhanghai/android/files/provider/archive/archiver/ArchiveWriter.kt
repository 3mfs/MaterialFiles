package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.archive.zip.FastZipWriter
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.libarchive.Archive
import java.io.Closeable
import java.io.IOException

class ArchiveWriter @Throws(IOException::class) constructor(
    channel: SeekableByteChannel,
    format: Int,
    filter: Int,
    password: String?,
    compressionLevel: ArchiveCompressionLevel = ArchiveCompressionLevel.NORMAL,
    sevenZMethod: SevenZCompressionMethod = SevenZCompressionMethod.LZMA2
) : Closeable {
    private val fastZipWriter: FastZipWriter? = if (format == Archive.FORMAT_ZIP) {
        FastZipWriter(channel, compressionLevel, password)
    } else {
        null
    }

    private val fastSevenZWriter: FastSevenZWriter? = if (
        format == Archive.FORMAT_7ZIP && (
            sevenZMethod == SevenZCompressionMethod.COPY ||
                (sevenZMethod == SevenZCompressionMethod.LZMA2 &&
                    FastSevenZWriter.isSupported(compressionLevel))
            )
    ) {
        FastSevenZWriter(channel, compressionLevel, sevenZMethod)
    } else {
        null
    }

    private val archive: WriteArchive? = if (fastZipWriter == null && fastSevenZWriter == null) {
        WriteArchive(channel, format, filter, password, compressionLevel, sevenZMethod)
    } else {
        null
    }

    @Throws(IOException::class)
    fun write(
        file: Path,
        entryName: Path,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?,
        attributes: BasicFileAttributes? = null
    ) {
        val name = entryName.toString()
        val resolvedAttributes = attributes ?: file.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val lastModifiedTime = resolvedAttributes.lastModifiedTime()
        val type = when {
            resolvedAttributes is PosixFileAttributes -> resolvedAttributes.type()
            resolvedAttributes.isDirectory -> PosixFileType.DIRECTORY
            resolvedAttributes.isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val size = resolvedAttributes.size()
        val posixAttributes = resolvedAttributes as? PosixFileAttributes
        val mode = posixAttributes?.mode() ?: when {
            resolvedAttributes.isDirectory -> PosixFileMode.DIRECTORY_DEFAULT
            resolvedAttributes.isSymbolicLink -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
            else -> PosixFileMode.FILE_DEFAULT
        }
        val symbolicLinkTarget = if (resolvedAttributes.isSymbolicLink) {
            file.readSymbolicLinkByteString().toString()
        } else {
            null
        }

        val zipWriter = fastZipWriter
        if (zipWriter != null) {
            when (type) {
                PosixFileType.DIRECTORY -> {
                    zipWriter.writeDirectory(name, lastModifiedTime, mode)
                    listener?.invoke(size)
                }
                PosixFileType.SYMBOLIC_LINK -> {
                    zipWriter.writeSymbolicLink(
                        name, symbolicLinkTarget.orEmpty(), lastModifiedTime, mode
                    )
                    listener?.invoke(size)
                }
                PosixFileType.REGULAR_FILE -> {
                    zipWriter.writeFileFromPath(
                        name, lastModifiedTime, mode, size, file, intervalMillis, listener
                    )
                }
                else -> {
                    listener?.invoke(size)
                }
            }
            return
        }

        val sevenZWriter = fastSevenZWriter
        if (sevenZWriter != null) {
            when (type) {
                PosixFileType.DIRECTORY -> {
                    sevenZWriter.writeDirectory(name, lastModifiedTime, mode)
                    listener?.invoke(size)
                }
                PosixFileType.SYMBOLIC_LINK -> {
                    sevenZWriter.writeSymbolicLink(
                        name, symbolicLinkTarget.orEmpty(), lastModifiedTime, mode
                    )
                    listener?.invoke(size)
                }
                PosixFileType.REGULAR_FILE -> {
                    sevenZWriter.writeFileFromPath(
                        name, lastModifiedTime, mode, file, intervalMillis, listener
                    )
                }
                else -> {
                    listener?.invoke(size)
                }
            }
            return
        }

        val libArchive = archive!!
        val owner = posixAttributes?.owner()
        val group = posixAttributes?.group()
        libArchive.Entry(
            name, lastModifiedTime, null, null, type, size, owner, group, mode,
            symbolicLinkTarget
        ).use { libArchive.writeEntry(it) }
        if (type == PosixFileType.REGULAR_FILE) {
            val channel = try {
                file.newByteChannel(LinkOption.NOFOLLOW_LINKS)
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: IOException) {
                null
            }
            if (channel != null) {
                channel.use { libArchive.writeData(it, intervalMillis, listener) }
            } else {
                file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                    inputStream.copyTo(libArchive.newDataOutputStream(), intervalMillis, listener)
                }
            }
        } else {
            listener?.invoke(size)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        fastZipWriter?.close()
        fastSevenZWriter?.close()
        archive?.close()
    }
}
