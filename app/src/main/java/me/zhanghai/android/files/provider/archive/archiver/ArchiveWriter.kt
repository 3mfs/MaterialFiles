/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

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
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.size
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

    private val archive: WriteArchive? = if (format != Archive.FORMAT_ZIP) {
        WriteArchive(channel, format, filter, password, compressionLevel, sevenZMethod)
    } else {
        null
    }

    @Throws(IOException::class)
    fun write(file: Path, entryName: Path, intervalMillis: Long, listener: ((Long) -> Unit)?) {
        val name = entryName.toString()
        val lastModifiedTime = file.getLastModifiedTime(LinkOption.NOFOLLOW_LINKS)
        val attributes = file.readAttributes(
            BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
        val type = when {
            attributes is PosixFileAttributes -> attributes.type()
            attributes.isDirectory -> PosixFileType.DIRECTORY
            attributes.isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val size = file.size(LinkOption.NOFOLLOW_LINKS)
        val posixAttributes = attributes as? PosixFileAttributes
        val mode = posixAttributes?.mode() ?: when {
            attributes.isDirectory -> PosixFileMode.DIRECTORY_DEFAULT
            attributes.isSymbolicLink -> PosixFileMode.SYMBOLIC_LINK_DEFAULT
            else -> PosixFileMode.FILE_DEFAULT
        }
        val symbolicLinkTarget = if (attributes.isSymbolicLink) {
            file.readSymbolicLinkByteString().toString()
        } else {
            null
        }

        val zipWriter = fastZipWriter
        if (zipWriter != null) {
            when (type) {
                PosixFileType.DIRECTORY -> {
                    zipWriter.writeDirectory(name, lastModifiedTime, mode)
                    listener?.invoke(attributes.size())
                }
                PosixFileType.SYMBOLIC_LINK -> {
                    zipWriter.writeSymbolicLink(
                        name, symbolicLinkTarget.orEmpty(), lastModifiedTime, mode
                    )
                    listener?.invoke(attributes.size())
                }
                PosixFileType.REGULAR_FILE -> {
                    file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                        zipWriter.writeFile(
                            name, lastModifiedTime, mode, size, inputStream, intervalMillis,
                            listener
                        )
                    }
                }
                else -> {
                    listener?.invoke(attributes.size())
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
            file.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
                inputStream.copyTo(libArchive.newDataOutputStream(), intervalMillis, listener)
            }
        } else {
            listener?.invoke(attributes.size())
        }
    }

    @Throws(IOException::class)
    override fun close() {
        fastZipWriter?.close()
        archive?.close()
    }
}
