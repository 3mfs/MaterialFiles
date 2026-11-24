/*
 * Copyright (c) 2026 Mox Apps
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.CRC32
import kotlin.math.min

/**
 * Multi-threaded native 7z writer using liblzma LZMA2 block parallelism (or raw Copy) with
 * buffered sequential I/O and a standard .7z signature header + kHeader trailer.
 */
internal class FastSevenZWriter(
    private val channel: SeekableByteChannel,
    private val compressionLevel: ArchiveCompressionLevel,
    private val sevenZMethod: SevenZCompressionMethod
) : Closeable {
    private val entries = mutableListOf<EntryMeta>()
    private val writeBuffer = ByteArray(WRITE_BUFFER_SIZE)
    private var writeBufferPos = 0
    private var packedSize = 0L
    private var totalUncompressedSize = 0L
    private var closed = false

    private val isLzma2 = sevenZMethod == SevenZCompressionMethod.LZMA2
    private val dictSize = dictSizeForLevel(compressionLevel)
    private val lzma2Prop: Byte

    private val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val executor: ExecutorService? = if (isLzma2) {
        Executors.newFixedThreadPool(workerCount)
    } else {
        null
    }
    private val blockPool = ConcurrentLinkedQueue<ByteArray>()
    private val blockQueue = ArrayDeque<Future<ByteArray>>()
    private var currentBlock = if (isLzma2) ByteArray(SOLID_BLOCK_SIZE) else EMPTY_BYTES
    private var currentBlockPos = 0

    init {
        if (isLzma2) {
            val propArr = ByteArray(1)
            val probe = Syscall.nativeLzma2CompressBlock(
                ByteArray(0), 0, compressionLevel.sevenZLevel, dictSize, propArr
            ) ?: throw IOException("Native LZMA2 encoder unavailable")
            // Keep static analyzer happy for probe.
            probe.size
            lzma2Prop = propArr[0]
        } else {
            lzma2Prop = 0
        }
        // Reserve 32 bytes at offset 0 for the 7z signature header.
        val placeholder = ByteArray(SIGNATURE_HEADER_SIZE)
        writeOut(placeholder, 0, placeholder.size)
        packedSize = 0L
    }

    @Throws(IOException::class)
    fun writeDirectory(
        entryName: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>
    ) {
        val normalized = entryName.trimStart('/').trimEnd('/')
        if (normalized.isEmpty()) return
        val fullMode = PosixFileType.DIRECTORY.mode or mode.toInt()
        entries += EntryMeta(
            name = normalized,
            isDirectory = true,
            size = 0L,
            mtimeMillis = lastModifiedTime?.toMillis() ?: 0L,
            hasMtime = lastModifiedTime != null,
            posixMode = fullMode,
            crc32 = 0L
        )
    }

    @Throws(IOException::class)
    fun writeSymbolicLink(
        entryName: String,
        target: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>
    ) {
        val bytes = target.toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32()
        if (bytes.isNotEmpty()) {
            crc.update(bytes, 0, bytes.size)
            feedPayload(bytes, 0, bytes.size)
        }
        val fullMode = PosixFileType.SYMBOLIC_LINK.mode or mode.toInt()
        entries += EntryMeta(
            name = entryName.trimStart('/'),
            isDirectory = false,
            size = bytes.size.toLong(),
            mtimeMillis = lastModifiedTime?.toMillis() ?: 0L,
            hasMtime = lastModifiedTime != null,
            posixMode = fullMode,
            crc32 = crc.value
        )
    }

    @Throws(IOException::class)
    fun writeFileFromPath(
        entryName: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>,
        sourcePath: Path,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ) {
        val cleanName = entryName.trimStart('/')
        val fullMode = PosixFileType.REGULAR_FILE.mode or mode.toInt()
        val crc = CRC32()
        var fileSize = 0L
        var lastProgressMillis = System.currentTimeMillis()
        var progressAcc = 0L

        sourcePath.newInputStream(LinkOption.NOFOLLOW_LINKS).use { input ->
            if (isLzma2) {
                while (true) {
                    if (currentBlockPos == SOLID_BLOCK_SIZE) {
                        submitCurrentSolidBlock()
                    }
                    val n = input.read(
                        currentBlock,
                        currentBlockPos,
                        SOLID_BLOCK_SIZE - currentBlockPos
                    )
                    if (n == -1) break
                    if (Thread.interrupted()) throw InterruptedIOException()
                    crc.update(currentBlock, currentBlockPos, n)
                    currentBlockPos += n
                    fileSize += n
                    totalUncompressedSize += n
                    progressAcc += n
                    val now = System.currentTimeMillis()
                    if (listener != null && now >= lastProgressMillis + intervalMillis) {
                        listener(progressAcc)
                        lastProgressMillis = now
                        progressAcc = 0L
                    }
                }
            } else {
                val copyBuf = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val n = input.read(copyBuf)
                    if (n == -1) break
                    if (Thread.interrupted()) throw InterruptedIOException()
                    crc.update(copyBuf, 0, n)
                    writeOut(copyBuf, 0, n)
                    packedSize += n
                    fileSize += n
                    totalUncompressedSize += n
                    progressAcc += n
                    val now = System.currentTimeMillis()
                    if (listener != null && now >= lastProgressMillis + intervalMillis) {
                        listener(progressAcc)
                        lastProgressMillis = now
                        progressAcc = 0L
                    }
                }
            }
        }
        if (progressAcc > 0 || fileSize == 0L) {
            listener?.invoke(progressAcc)
        }

        entries += EntryMeta(
            name = cleanName,
            isDirectory = false,
            size = fileSize,
            mtimeMillis = lastModifiedTime?.toMillis() ?: 0L,
            hasMtime = lastModifiedTime != null,
            posixMode = fullMode,
            crc32 = crc.value
        )
    }

    @Throws(IOException::class)
    private fun feedPayload(bytes: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (!isLzma2) {
            writeOut(bytes, off, len)
            packedSize += len
            totalUncompressedSize += len
            return
        }
        var srcOff = off
        var remaining = len
        while (remaining > 0) {
            if (currentBlockPos == SOLID_BLOCK_SIZE) {
                submitCurrentSolidBlock()
            }
            val chunk = min(remaining, SOLID_BLOCK_SIZE - currentBlockPos)
            System.arraycopy(bytes, srcOff, currentBlock, currentBlockPos, chunk)
            currentBlockPos += chunk
            srcOff += chunk
            remaining -= chunk
            totalUncompressedSize += chunk
        }
    }

    @Throws(IOException::class)
    private fun submitCurrentSolidBlock() {
        val block = currentBlock
        val blockLen = currentBlockPos
        currentBlock = borrowBlock()
        currentBlockPos = 0
        if (blockLen <= 0) {
            releaseBlock(block)
            return
        }
        val level = compressionLevel.sevenZLevel
        val dict = dictSize
        val future = executor!!.submit(Callable {
            try {
                Syscall.nativeLzma2CompressBlock(block, blockLen, level, dict, null)
                    ?: throw IOException("Native LZMA2 block compression failed")
            } finally {
                releaseBlock(block)
            }
        })
        blockQueue.addLast(future)
        if (blockQueue.size >= workerCount) {
            drainOneBlock()
        }
    }

    @Throws(IOException::class)
    private fun drainOneBlock() {
        val future = blockQueue.pollFirst() ?: return
        val compressed = try {
            future.get()
        } catch (e: InterruptedException) {
            cancelAllBlocks()
            Thread.currentThread().interrupt()
            throw InterruptedIOException().apply { initCause(e) }
        } catch (e: ExecutionException) {
            cancelAllBlocks()
            val cause = e.cause
            if (cause is IOException) throw cause
            throw IOException(cause)
        }
        if (compressed.isNotEmpty()) {
            writeOut(compressed, 0, compressed.size)
            packedSize += compressed.size
        }
    }

    private fun cancelAllBlocks() {
        while (blockQueue.isNotEmpty()) {
            blockQueue.pollFirst()?.cancel(true)
        }
    }

    private fun borrowBlock(): ByteArray =
        blockPool.poll() ?: ByteArray(SOLID_BLOCK_SIZE)

    private fun releaseBlock(block: ByteArray) {
        if (blockPool.size < workerCount + 2) {
            blockPool.offer(block)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (isLzma2) {
                if (currentBlockPos > 0) {
                    submitCurrentSolidBlock()
                }
                while (blockQueue.isNotEmpty()) {
                    drainOneBlock()
                }
                if (totalUncompressedSize > 0L) {
                    // Single LZMA2 end-of-stream marker byte.
                    writeOutByte(0)
                    packedSize += 1L
                }
            }
            writeHeaderAndPatchSignature()
            flushOut()
        } finally {
            cancelAllBlocks()
            blockPool.clear()
            executor?.shutdownNow()
            channel.close()
        }
    }

    @Throws(IOException::class)
    private fun writeHeaderAndPatchSignature() {
        val headerBytes = buildHeaderBytes()
        val headerCrc = CRC32().apply { update(headerBytes) }.value

        val nextHeaderOffset = packedSize
        writeOut(headerBytes, 0, headerBytes.size)
        flushOut()

        val sigHeader = ByteBuffer.allocate(SIGNATURE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        sigHeader.put(byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C))
        sigHeader.put(0x00.toByte())
        sigHeader.put(0x03.toByte())
        sigHeader.putInt(0) // StartHeaderCRC placeholder
        sigHeader.putLong(nextHeaderOffset)
        sigHeader.putLong(headerBytes.size.toLong())
        sigHeader.putInt(headerCrc.toInt())

        val sigArr = sigHeader.array()
        val startHeaderCrc = CRC32().apply { update(sigArr, 12, 20) }.value
        sigHeader.putInt(8, startHeaderCrc.toInt())

        sigHeader.position(0)
        channel.position(0L)
        while (sigHeader.hasRemaining()) {
            channel.write(sigHeader)
        }
    }

    private fun buildHeaderBytes(): ByteArray {
        val out = ByteArrayOutputStream(256 + entries.size * 64)
        out.write(ID_HEADER)

        val nonEmptyFiles = entries.filter { !it.isDirectory && it.size > 0L }
        if (nonEmptyFiles.isNotEmpty()) {
            out.write(ID_MAIN_STREAMS_INFO)

            // PackInfo
            out.write(ID_PACK_INFO)
            writeUint64(out, 0L)
            writeUint64(out, 1L)
            out.write(ID_SIZE)
            writeUint64(out, packedSize)
            out.write(ID_END)

            // UnPackInfo
            out.write(ID_UNPACK_INFO)
            out.write(ID_FOLDER)
            writeUint64(out, 1L) // NumFolders
            out.write(0x00) // External = 0
            writeUint64(out, 1L) // NumCoders
            if (isLzma2) {
                out.write(0x21) // idSize=1, hasAttributes=1
                out.write(0x21) // CodecId = LZMA2
                writeUint64(out, 1L) // PropertiesSize
                out.write(lzma2Prop.toInt() and 0xFF)
            } else {
                out.write(0x01) // idSize=1, hasAttributes=0
                out.write(0x00) // CodecId = Copy
            }
            out.write(ID_CODERS_UNPACK_SIZE)
            writeUint64(out, totalUncompressedSize)
            out.write(ID_END)

            // SubStreamsInfo
            out.write(ID_SUBSTREAMS_INFO)
            if (nonEmptyFiles.size > 1) {
                out.write(ID_NUM_UNPACK_STREAM)
                writeUint64(out, nonEmptyFiles.size.toLong())
                out.write(ID_SIZE)
                for (i in 0 until nonEmptyFiles.size - 1) {
                    writeUint64(out, nonEmptyFiles[i].size)
                }
            }
            out.write(ID_CRC)
            out.write(0x01) // AllAreDefined = 1
            for (file in nonEmptyFiles) {
                writeIntLe(out, file.crc32.toInt())
            }
            out.write(ID_END)

            out.write(ID_END) // end MainStreamsInfo
        }

        if (entries.isNotEmpty()) {
            out.write(ID_FILES_INFO)
            writeUint64(out, entries.size.toLong())

            val emptyEntries = entries.filter { it.isDirectory || it.size == 0L }
            if (emptyEntries.isNotEmpty()) {
                val emptyBits = packBoolBits(entries.map { it.isDirectory || it.size == 0L })
                out.write(ID_EMPTY_STREAM)
                writeUint64(out, emptyBits.size.toLong())
                out.write(emptyBits)

                val emptyFiles = emptyEntries.filter { !it.isDirectory }
                if (emptyFiles.isNotEmpty()) {
                    val emptyFileBits = packBoolBits(emptyEntries.map { !it.isDirectory })
                    out.write(ID_EMPTY_FILE)
                    writeUint64(out, emptyFileBits.size.toLong())
                    out.write(emptyFileBits)
                }
            }

            // kName
            val nameOut = ByteArrayOutputStream(entries.size * 32)
            nameOut.write(0x00) // External = 0
            for (entry in entries) {
                nameOut.write(entry.name.toByteArray(StandardCharsets.UTF_16LE))
                nameOut.write(0x00)
                nameOut.write(0x00)
            }
            val nameBytes = nameOut.toByteArray()
            out.write(ID_NAME)
            writeUint64(out, nameBytes.size.toLong())
            out.write(nameBytes)

            // kMTime
            if (entries.any { it.hasMtime }) {
                val mtimeOut = ByteArrayOutputStream(entries.size * 8 + 16)
                if (entries.all { it.hasMtime }) {
                    mtimeOut.write(0x01)
                } else {
                    mtimeOut.write(0x00)
                    mtimeOut.write(packBoolBits(entries.map { it.hasMtime }))
                }
                mtimeOut.write(0x00) // External = 0
                for (entry in entries) {
                    if (entry.hasMtime) {
                        val fileTime = (entry.mtimeMillis + EPOCH_DIFF_MILLIS) * 10000L
                        writeLongLe(mtimeOut, fileTime)
                    }
                }
                val mtimeBytes = mtimeOut.toByteArray()
                out.write(ID_MTIME)
                writeUint64(out, mtimeBytes.size.toLong())
                out.write(mtimeBytes)
            }

            // kWinAttributes (encodes POSIX mode in upper 16 bits + Unix extension bit 0x8000)
            val attrOut = ByteArrayOutputStream(2 + entries.size * 4)
            attrOut.write(0x01) // AllAreDefined = 1
            attrOut.write(0x00) // External = 0
            for (entry in entries) {
                val winAttr = (entry.posixMode shl 16) or 0x8000 or
                    (if (entry.isDirectory) 0x10 else 0x20)
                writeIntLe(attrOut, winAttr)
            }
            val attrBytes = attrOut.toByteArray()
            out.write(ID_WIN_ATTRIBUTES)
            writeUint64(out, attrBytes.size.toLong())
            out.write(attrBytes)

            out.write(ID_END) // end FilesInfo
        }

        out.write(ID_END) // end Header
        return out.toByteArray()
    }

    @Throws(IOException::class)
    private fun writeOutByte(b: Int) {
        if (writeBufferPos == writeBuffer.size) {
            flushOut()
        }
        writeBuffer[writeBufferPos++] = b.toByte()
    }

    @Throws(IOException::class)
    private fun writeOut(b: ByteArray, off: Int, len: Int) {
        var remaining = len
        var srcOff = off
        while (remaining > 0) {
            if (writeBufferPos == writeBuffer.size) {
                flushOut()
            }
            val chunk = min(remaining, writeBuffer.size - writeBufferPos)
            System.arraycopy(b, srcOff, writeBuffer, writeBufferPos, chunk)
            writeBufferPos += chunk
            srcOff += chunk
            remaining -= chunk
        }
    }

    @Throws(IOException::class)
    private fun flushOut() {
        if (writeBufferPos == 0) return
        val bb = ByteBuffer.wrap(writeBuffer, 0, writeBufferPos)
        while (bb.hasRemaining()) {
            channel.write(bb)
        }
        writeBufferPos = 0
    }

    private fun packBoolBits(flags: List<Boolean>): ByteArray {
        val bytes = ByteArray((flags.size + 7) ushr 3)
        for (i in flags.indices) {
            if (flags[i]) {
                bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
            }
        }
        return bytes
    }

    private fun writeUint64(out: ByteArrayOutputStream, value: Long) {
        var mask = 0x80
        var firstByte = 0
        for (i in 0 until 8) {
            if (value < (1L shl (7 * (i + 1))) && (i == 0 || value >= 0)) {
                val extra = i
                firstByte = firstByte or ((value ushr (8 * extra)).toInt() and (mask - 1))
                out.write(firstByte)
                for (j in 0 until extra) {
                    out.write((value ushr (8 * j)).toInt() and 0xFF)
                }
                return
            }
            firstByte = firstByte or mask
            mask = mask ushr 1
        }
        out.write(0xFF)
        writeLongLe(out, value)
    }

    private fun writeIntLe(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeLongLe(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) {
            out.write(((v ushr (8 * i)) and 0xFFL).toInt())
        }
    }

    private class EntryMeta(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val mtimeMillis: Long,
        val hasMtime: Boolean,
        val posixMode: Int,
        val crc32: Long
    )

    companion object {
        private const val SIGNATURE_HEADER_SIZE = 32
        private const val IO_BUFFER_SIZE = 128 * 1024
        private const val WRITE_BUFFER_SIZE = 512 * 1024
        private const val SOLID_BLOCK_SIZE = 2 * 1024 * 1024
        private const val EPOCH_DIFF_MILLIS = 11644473600000L

        private const val ID_END = 0x00
        private const val ID_HEADER = 0x01
        private const val ID_MAIN_STREAMS_INFO = 0x04
        private const val ID_FILES_INFO = 0x05
        private const val ID_PACK_INFO = 0x06
        private const val ID_UNPACK_INFO = 0x07
        private const val ID_SUBSTREAMS_INFO = 0x08
        private const val ID_SIZE = 0x09
        private const val ID_CRC = 0x0A
        private const val ID_FOLDER = 0x0B
        private const val ID_CODERS_UNPACK_SIZE = 0x0C
        private const val ID_NUM_UNPACK_STREAM = 0x0D
        private const val ID_EMPTY_STREAM = 0x0E
        private const val ID_EMPTY_FILE = 0x0F
        private const val ID_NAME = 0x11
        private const val ID_MTIME = 0x14
        private const val ID_WIN_ATTRIBUTES = 0x15

        private val EMPTY_BYTES = ByteArray(0)

        private fun dictSizeForLevel(level: ArchiveCompressionLevel): Int = when (level) {
            ArchiveCompressionLevel.STORE, ArchiveCompressionLevel.FAST -> 1 shl 20
            ArchiveCompressionLevel.NORMAL -> 2 shl 20
            ArchiveCompressionLevel.MAXIMUM -> 4 shl 20
        }

        fun isSupported(compressionLevel: ArchiveCompressionLevel): Boolean =
            try {
                Syscall.nativeLzma2CompressBlock(
                    ByteArray(0), 0, compressionLevel.sevenZLevel,
                    dictSizeForLevel(compressionLevel), ByteArray(1)
                ) != null
            } catch (_: Throwable) {
                false
            }
    }
}
