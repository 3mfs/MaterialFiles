package com.moxapps.files.provider.archive.archiver

import android.system.Os
import java8.nio.channels.SeekableByteChannel
import java8.nio.charset.StandardCharsets
import java8.nio.file.attribute.FileTime
import com.moxapps.files.app.application
import com.moxapps.files.provider.common.PosixFileModeBit
import com.moxapps.files.provider.common.PosixFileType
import com.moxapps.files.provider.common.PosixGroup
import com.moxapps.files.provider.common.PosixUser
import com.moxapps.files.provider.common.toInt
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.min

class WriteArchive @Throws(ArchiveException::class) constructor(
    private val channel: SeekableByteChannel,
    format: Int,
    filter: Int,
    password: String?,
    compressionLevel: ArchiveCompressionLevel = ArchiveCompressionLevel.NORMAL,
    sevenZMethod: SevenZCompressionMethod = SevenZCompressionMethod.LZMA2
) : Closeable {
    private val archive = Archive.writeNew()
    private val directBuffer = ByteBuffer.allocateDirect(IO_BUFFER_SIZE)
    private val outputBuffer = ByteBuffer.allocateDirect(IO_BUFFER_SIZE)

    init {
        ensureTempDir()
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
                archive,
                null,
                null,
                { _, _, buffer -> writeToOutputBuffer(buffer) },
                { _, _ -> flushOutputBuffer() }
            )
            successful = true
        } finally {
            if (!successful) {
                close()
            }
        }
    }

    private fun writeToOutputBuffer(buffer: ByteBuffer) {
        val incoming = buffer.remaining()
        if (incoming == 0) return
        if (incoming > outputBuffer.remaining()) {
            flushOutputBuffer()
            if (incoming >= outputBuffer.capacity()) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                return
            }
        }
        outputBuffer.put(buffer)
    }

    private fun flushOutputBuffer() {
        outputBuffer.flip()
        while (outputBuffer.hasRemaining()) {
            channel.write(outputBuffer)
        }
        outputBuffer.clear()
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

    @Throws(IOException::class)
    fun writeData(
        inputChannel: SeekableByteChannel,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ) {
        var lastProgressMillis = System.currentTimeMillis()
        var copiedSize = 0L
        while (true) {
            directBuffer.clear()
            val readSize = inputChannel.read(directBuffer)
            if (readSize == -1) {
                break
            }
            if (readSize == 0) {
                continue
            }
            if (Thread.interrupted()) {
                throw InterruptedIOException()
            }
            directBuffer.flip()
            while (directBuffer.hasRemaining()) {
                Archive.writeData(archive, directBuffer)
            }
            copiedSize += readSize.toLong()
            val currentTimeMillis = System.currentTimeMillis()
            if (listener != null && currentTimeMillis >= lastProgressMillis + intervalMillis) {
                listener(copiedSize)
                lastProgressMillis = currentTimeMillis
                copiedSize = 0L
            }
        }
        listener?.invoke(copiedSize)
    }

    @Throws(ArchiveException::class)
    fun newDataOutputStream(): OutputStream = DataOutputStream()

    @Throws(ArchiveException::class)
    override fun close() {
        try {
            Archive.writeFree(archive)
        } finally {
            flushOutputBuffer()
        }
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
        private const val IO_BUFFER_SIZE = 256 * 1024

        @Volatile
        private var tempDirInitialized = false

        private fun ensureTempDir() {
            if (tempDirInitialized) return
            tempDirInitialized = true
            try {
                Os.setenv("TMPDIR", application.cacheDir.absolutePath, false)
            } catch (_: Exception) {
            }
        }
    }
}
