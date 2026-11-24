/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.min

class WriteArchive @Throws(ArchiveException::class) constructor(
    channel: SeekableByteChannel,
    format: Int,
    filter: Int,
    password: String?,
    compressionLevel: ArchiveCompressionLevel = ArchiveCompressionLevel.NORMAL,
    sevenZMethod: SevenZCompressionMethod = SevenZCompressionMethod.LZMA2
) : Closeable {
    private val archive = Archive.writeNew()

    init {
        var successful = false
        try {
            Archive.writeSetBytesPerBlock(archive, IO_BUFFER_SIZE)
            Archive.writeSetBytesInLastBlock(archive, 1)
            Archive.writeSetFormat(archive, format)
            Archive.writeAddFilter(archive, filter)
            when (format) {
                Archive.FORMAT_ZIP -> {
                    if (compressionLevel == ArchiveCompressionLevel.STORE) {
                        trySetFormatOption("compression", "store")
                    } else {
                        trySetFormatOption("compression-level", compressionLevel.zipLevel.toString())
                    }
                }
                Archive.FORMAT_7ZIP -> {
                    trySetFormatOption("compression", sevenZMethod.libArchiveValue)
                    if (sevenZMethod != SevenZCompressionMethod.COPY) {
                        trySetFormatOption(
                            "compression-level", compressionLevel.sevenZLevel.toString()
                        )
                        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                        trySetFormatOption("threads", threads.toString())
                    }
                }
            }
            if (filter == Archive.FILTER_XZ) {
                trySetFilterOption("compression-level", compressionLevel.xzLevel.toString())
                val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                trySetFilterOption("threads", threads.toString())
            }
            if (password != null) {
                require(format == Archive.FORMAT_ZIP)
                Archive.writeSetPassphrase(archive, password.toByteArray())
                // Nautilus uses AES-256 encryption as well, as it uses gnome-autoar where
                // autoar_compressor_step_initialize_object() sets encryption to aes256.
                Archive.writeSetFormatOption(
                    archive, null, "encryption".toByteArray(), "aes256".toByteArray()
                )
            }
            Archive.writeOpen(
                archive, null, null, { _, _, buffer -> channel.write(buffer) }, null
            )
            successful = true
        } finally {
            if (!successful) {
                close()
            }
        }
    }

    private fun trySetFormatOption(key: String, value: String) {
        try {
            Archive.writeSetFormatOption(
                archive, null, key.toByteArray(), value.toByteArray()
            )
        } catch (_: ArchiveException) {
        }
    }

    private fun trySetFilterOption(key: String, value: String) {
        try {
            Archive.writeSetFilterOption(
                archive, null, key.toByteArray(), value.toByteArray()
            )
        } catch (_: ArchiveException) {
        }
    }

    @Throws(ArchiveException::class)
    fun writeEntry(entry: Entry) {
        Archive.writeHeader(archive, entry.entry)
    }

    @Throws(ArchiveException::class)
    fun newDataOutputStream(): OutputStream = DataOutputStream()

    @Throws(ArchiveException::class)
    override fun close() {
        Archive.writeFree(archive)
    }

    inner class Entry(
        name: String,
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        creationTime: FileTime?,
        type: PosixFileType,
        size: Long,
        owner: PosixUser?,
        group: PosixGroup?,
        mode: Set<PosixFileModeBit>,
        symbolicLinkTarget: String?
    ) : Closeable {
        internal val entry = ArchiveEntry.new2(archive)

        init {
            Archive.setCharset(archive, StandardCharsets.UTF_8.name().toByteArray())
            ArchiveEntry.setPathname(entry, name.toByteArray())
            if (lastModifiedTime != null) {
                val lastModifiedTimeInstant = lastModifiedTime.toInstant()
                ArchiveEntry.setMtime(
                    entry, lastModifiedTimeInstant.epochSecond,
                    lastModifiedTimeInstant.nano.toLong()
                )
            }
            if (lastAccessTime != null) {
                val lastAccessTimeInstant = lastAccessTime.toInstant()
                ArchiveEntry.setAtime(
                    entry, lastAccessTimeInstant.epochSecond, lastAccessTimeInstant.nano.toLong()
                )
            }
            if (creationTime != null) {
                val creationTimeInstant = creationTime.toInstant()
                ArchiveEntry.setBirthtime(
                    entry, creationTimeInstant.epochSecond, creationTimeInstant.nano.toLong()
                )
            }
            ArchiveEntry.setFiletype(entry, type.mode)
            ArchiveEntry.setSize(entry, size)
            if (owner != null) {
                ArchiveEntry.setUid(entry, owner.id.toLong())
                val ownerName = owner.name
                if (ownerName != null) {
                    ArchiveEntry.setUname(entry, ownerName.toByteArray())
                }
            }
            if (group != null) {
                ArchiveEntry.setGid(entry, group.id.toLong())
                val groupName = group.name
                if (groupName != null) {
                    ArchiveEntry.setGname(entry, groupName.toByteArray())
                }
            }
            ArchiveEntry.setPerm(entry, mode.toInt())
            if (symbolicLinkTarget != null) {
                ArchiveEntry.setSymlink(entry, symbolicLinkTarget.toByteArray())
            }
        }

        override fun close() {
            ArchiveEntry.free(entry)
        }
    }

    private inner class DataOutputStream : OutputStream() {
        private val directBuffer = ByteBuffer.allocateDirect(IO_BUFFER_SIZE)

        @Throws(IOException::class)
        override fun write(b: Int) {
            directBuffer.clear()
            directBuffer.put(b.toByte())
            directBuffer.flip()
            Archive.writeData(archive, directBuffer)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var remaining = len
            var offset = off
            while (remaining > 0) {
                val chunk = min(remaining, directBuffer.capacity())
                directBuffer.clear()
                directBuffer.put(b, offset, chunk)
                directBuffer.flip()
                while (directBuffer.hasRemaining()) {
                    Archive.writeData(archive, directBuffer)
                }
                offset += chunk
                remaining -= chunk
            }
        }
    }

    companion object {
        private const val IO_BUFFER_SIZE = 64 * 1024
    }
}
