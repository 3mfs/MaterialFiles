/*
 * Copyright (c) 2026 MaterialFilesExtended Contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.zip

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.archive.archiver.ArchiveCompressionLevel
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.toInt
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import kotlin.math.min

/**
 * Pure-JVM high-throughput ZIP writer operating on java8.nio.channels.SeekableByteChannel.
 * Supports compression levels (Store/Fast/Normal/Maximum), WinZip AE-2 AES-256, ZIP64,
 * and raw-entry pass-through for zero-recompression in-place editing.
 */
internal class FastZipWriter(
    private val channel: SeekableByteChannel,
    private val compressionLevel: ArchiveCompressionLevel,
    private val password: String?
) : Closeable {
    private val entries = mutableListOf<WrittenEntry>()
    private val writeBuffer = ByteArray(IO_BUFFER_SIZE)
    private var writeBufferPos = 0
    private var currentPosition = 0L
    private var closed = false

    @Throws(IOException::class)
    fun writeDirectory(
        entryName: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>
    ) {
        val normalized = normalizeDirName(entryName)
        writeStreamEntry(
            name = normalized,
            isDirectory = true,
            isSymbolicLink = false,
            lastModifiedTime = lastModifiedTime,
            mode = mode,
            uncompressedSizeHint = 0L,
            inputStream = null,
            intervalMillis = 0L,
            listener = null
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
        bytes.inputStream().use { input ->
            writeStreamEntry(
                name = entryName.trimStart('/'),
                isDirectory = false,
                isSymbolicLink = true,
                lastModifiedTime = lastModifiedTime,
                mode = mode,
                uncompressedSizeHint = bytes.size.toLong(),
                inputStream = input,
                intervalMillis = 0L,
                listener = null
            )
        }
    }

    @Throws(IOException::class)
    fun writeFile(
        entryName: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>,
        uncompressedSizeHint: Long,
        inputStream: InputStream,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ) {
        writeStreamEntry(
            name = entryName.trimStart('/'),
            isDirectory = false,
            isSymbolicLink = false,
            lastModifiedTime = lastModifiedTime,
            mode = mode,
            uncompressedSizeHint = uncompressedSizeHint,
            inputStream = inputStream,
            intervalMillis = intervalMillis,
            listener = listener
        )
    }

    @Throws(IOException::class)
    private fun writeStreamEntry(
        name: String,
        isDirectory: Boolean,
        isSymbolicLink: Boolean,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>,
        uncompressedSizeHint: Long,
        inputStream: InputStream?,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val useAes = password != null && !isDirectory
        val actualMethod = if (isDirectory || compressionLevel == ArchiveCompressionLevel.STORE) {
            ZipEntry.STORED
        } else {
            ZipEntry.DEFLATED
        }
        val headerMethod = if (useAes) WinZipAes.METHOD_AES else actualMethod
        val gpbf = GPBF_UTF8 or (if (useAes) GPBF_ENCRYPTED else 0)
        val (dosTime, dosDate) = toDosTimeDate(lastModifiedTime)
        val mtimeSeconds = lastModifiedTime?.toInstant()?.epochSecond
        val posixType = when {
            isDirectory -> PosixFileType.DIRECTORY
            isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val fullMode = posixType.mode or mode.toInt()
        val extAttr = (fullMode shl 16) or (if (isDirectory) 0x10 else 0)
        val versionMadeBy = (MADE_BY_UNIX shl 8) or 45

        val needsZip64Placeholder = !isDirectory && uncompressedSizeHint >= ZIP64_MAGIC_SIZE
        val aesExtra = if (useAes) buildAesExtra(WinZipAes.STRENGTH_256, actualMethod) else EMPTY_BYTES
        val timeExtra = buildExtendedTimestampExtra(mtimeSeconds)
        val zip64LocalExtra = if (needsZip64Placeholder) ByteArray(20) else EMPTY_BYTES
        val localExtra = zip64LocalExtra + aesExtra + timeExtra

        val localHeaderOffset = currentPosition
        val versionNeeded = if (needsZip64Placeholder || useAes) 51 else 20

        // Write Local File Header with 0 sizes/crc placeholders; we'll seek back to patch them.
        val lfh = ByteBuffer.allocate(30 + nameBytes.size + localExtra.size).order(ByteOrder.LITTLE_ENDIAN)
        lfh.putInt(SIG_LOCAL_FILE_HEADER)
        lfh.putShort(versionNeeded.toShort())
        lfh.putShort(gpbf.toShort())
        lfh.putShort(headerMethod.toShort())
        lfh.putShort(dosTime.toShort())
        lfh.putShort(dosDate.toShort())
        lfh.putInt(0) // crc32 placeholder
        lfh.putInt(if (needsZip64Placeholder) ZIP64_MAGIC_SIZE.toInt() else 0)
        lfh.putInt(if (needsZip64Placeholder) ZIP64_MAGIC_SIZE.toInt() else 0)
        lfh.putShort(nameBytes.size.toShort())
        lfh.putShort(localExtra.size.toShort())
        lfh.put(nameBytes)
        lfh.put(localExtra)
        writeBytes(lfh.array(), 0, lfh.capacity())

        var uncompressedSize = 0L
        var compressedSize = 0L
        var crcValue = 0L

        if (!isDirectory && inputStream != null) {
            val crc = CRC32()
            val encryptor = if (useAes) {
                WinZipAes.createEncryptor(password!!, WinZipAes.STRENGTH_256)
            } else {
                null
            }
            try {
                if (encryptor != null) {
                    writeBytes(encryptor.salt, 0, encryptor.salt.size)
                    writeBytes(encryptor.verifier, 0, encryptor.verifier.size)
                    compressedSize += encryptor.salt.size + encryptor.verifier.size
                }

                val payloadOut = object : OutputStream() {
                    override fun write(b: Int) {
                        write(byteArrayOf(b.toByte()), 0, 1)
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (len <= 0) return
                        if (encryptor != null) {
                            encryptor.encrypt(b, off, len)
                        }
                        writeBytes(b, off, len)
                        compressedSize += len
                    }
                }

                val ioBuf = ByteArray(IO_BUFFER_SIZE)
                var lastProgressMillis = System.currentTimeMillis()
                var progressAcc = 0L

                if (actualMethod == ZipEntry.STORED) {
                    while (true) {
                        val n = inputStream.read(ioBuf)
                        if (n <= 0) break
                        if (Thread.interrupted()) throw InterruptedIOException()
                        if (!useAes) crc.update(ioBuf, 0, n)
                        payloadOut.write(ioBuf, 0, n)
                        uncompressedSize += n
                        progressAcc += n
                        val now = System.currentTimeMillis()
                        if (listener != null && now >= lastProgressMillis + intervalMillis) {
                            listener(progressAcc)
                            lastProgressMillis = now
                            progressAcc = 0L
                        }
                    }
                } else {
                    val deflater = Deflater(compressionLevel.zipLevel, true)
                    val deflateBuf = ByteArray(IO_BUFFER_SIZE)
                    try {
                        while (true) {
                            val n = inputStream.read(ioBuf)
                            if (n <= 0) break
                            if (Thread.interrupted()) throw InterruptedIOException()
                            if (!useAes) crc.update(ioBuf, 0, n)
                            deflater.setInput(ioBuf, 0, n)
                            while (!deflater.needsInput()) {
                                val c = deflater.deflate(deflateBuf)
                                if (c > 0) payloadOut.write(deflateBuf, 0, c)
                            }
                            uncompressedSize += n
                            progressAcc += n
                            val now = System.currentTimeMillis()
                            if (listener != null && now >= lastProgressMillis + intervalMillis) {
                                listener(progressAcc)
                                lastProgressMillis = now
                                progressAcc = 0L
                            }
                        }
                        deflater.finish()
                        while (!deflater.finished()) {
                            val c = deflater.deflate(deflateBuf)
                            if (c > 0) payloadOut.write(deflateBuf, 0, c)
                        }
                    } finally {
                        deflater.end()
                    }
                }
                if (progressAcc > 0) {
                    listener?.invoke(progressAcc)
                }
                if (encryptor != null) {
                    val authCode = encryptor.finishAuthCode()
                    writeBytes(authCode, 0, authCode.size)
                    compressedSize += authCode.size
                }
                crcValue = if (useAes) 0L else crc.value
            } finally {
                encryptor?.close()
            }
        } else {
            listener?.invoke(0L)
        }

        // Flush buffer and patch Local File Header sizes & CRC.
        flushBuffer()
        val needsZip64Sizes = uncompressedSize >= ZIP64_MAGIC_SIZE || compressedSize >= ZIP64_MAGIC_SIZE
        val lfhCrcField = crcValue.toInt()
        val lfhCompField = if (needsZip64Sizes || needsZip64Placeholder) ZIP64_MAGIC_SIZE.toInt() else compressedSize.toInt()
        val lfhUncompField = if (needsZip64Sizes || needsZip64Placeholder) ZIP64_MAGIC_SIZE.toInt() else uncompressedSize.toInt()

        val patchBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        patchBuf.putInt(lfhCrcField)
        patchBuf.putInt(lfhCompField)
        patchBuf.putInt(lfhUncompField)
        patchBuf.flip()
        channel.position(localHeaderOffset + 14L)
        while (patchBuf.hasRemaining()) channel.write(patchBuf)

        if (needsZip64Placeholder) {
            val z64Patch = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            z64Patch.putShort(EXTRA_ZIP64.toShort())
            z64Patch.putShort(16.toShort())
            z64Patch.putLong(uncompressedSize)
            z64Patch.putLong(compressedSize)
            z64Patch.flip()
            channel.position(localHeaderOffset + 30L + nameBytes.size)
            while (z64Patch.hasRemaining()) channel.write(z64Patch)
        }
        channel.position(currentPosition)

        entries += WrittenEntry(
            nameBytes = nameBytes,
            versionMadeBy = versionMadeBy,
            versionNeeded = if (needsZip64Sizes || localHeaderOffset >= ZIP64_MAGIC_SIZE || useAes) 51 else 20,
            gpbf = gpbf,
            method = headerMethod,
            dosTime = dosTime,
            dosDate = dosDate,
            crc32 = crcValue,
            compressedSize = compressedSize,
            uncompressedSize = uncompressedSize,
            externalAttributes = extAttr,
            localHeaderOffset = localHeaderOffset,
            aesExtra = aesExtra,
            timeExtra = timeExtra
        )
    }

    /**
     * Copies an existing ZIP entry's raw compressed bytes without decompression or recompression.
     */
    @Throws(IOException::class)
    fun copyRawEntry(
        sourceChannel: SeekableByteChannel,
        entry: FastZipFile.ZipEntryInfo,
        newName: String = if (entry.isDirectory) "${entry.name}/" else entry.name
    ) {
        val (srcDataOffset, srcCompSize) = FastZipFile.getEntryDataRange(sourceChannel, entry)
        val useUtf8 = (entry.gpbf and GPBF_UTF8) != 0
        val newNameBytes = if (useUtf8 || newName != (if (entry.isDirectory) "${entry.name}/" else entry.name)) {
            newName.toByteArray(StandardCharsets.UTF_8)
        } else {
            entry.rawNameBytes
        }
        val gpbf = (entry.gpbf and GPBF_DATA_DESCRIPTOR.inv()) or
            (if (newNameBytes !== entry.rawNameBytes) GPBF_UTF8 else 0)

        val useAes = entry.method == WinZipAes.METHOD_AES
        val aesExtra = if (useAes) {
            buildAesExtra(entry.aesStrength, entry.actualMethod, entry.aesVersion)
        } else {
            EMPTY_BYTES
        }
        val timeExtra = buildExtendedTimestampExtra(entry.lastModifiedTime?.toInstant()?.epochSecond)
        val needsZip64Sizes = entry.uncompressedSize >= ZIP64_MAGIC_SIZE || entry.compressedSize >= ZIP64_MAGIC_SIZE
        val zip64LocalExtra = if (needsZip64Sizes) {
            ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(EXTRA_ZIP64.toShort())
                putShort(16.toShort())
                putLong(entry.uncompressedSize)
                putLong(entry.compressedSize)
            }.array()
        } else {
            EMPTY_BYTES
        }
        val localExtra = zip64LocalExtra + aesExtra + timeExtra
        val localHeaderOffset = currentPosition
        val versionNeeded = if (needsZip64Sizes || localHeaderOffset >= ZIP64_MAGIC_SIZE || useAes) 51 else 20

        val lfh = ByteBuffer.allocate(30 + newNameBytes.size + localExtra.size).order(ByteOrder.LITTLE_ENDIAN)
        lfh.putInt(SIG_LOCAL_FILE_HEADER)
        lfh.putShort(versionNeeded.toShort())
        lfh.putShort(gpbf.toShort())
        lfh.putShort(entry.method.toShort())
        lfh.putShort(entry.dosTime.toShort())
        lfh.putShort(entry.dosDate.toShort())
        lfh.putInt(entry.crc32.toInt())
        lfh.putInt(if (needsZip64Sizes) ZIP64_MAGIC_SIZE.toInt() else entry.compressedSize.toInt())
        lfh.putInt(if (needsZip64Sizes) ZIP64_MAGIC_SIZE.toInt() else entry.uncompressedSize.toInt())
        lfh.putShort(newNameBytes.size.toShort())
        lfh.putShort(localExtra.size.toShort())
        lfh.put(newNameBytes)
        lfh.put(localExtra)
        writeBytes(lfh.array(), 0, lfh.capacity())

        // Stream raw compressed payload directly.
        if (srcCompSize > 0) {
            val copyBuf = ByteArray(IO_BUFFER_SIZE)
            var remaining = srcCompSize
            var srcPos = srcDataOffset
            while (remaining > 0) {
                val toRead = min(remaining, copyBuf.size.toLong()).toInt()
                val bb = ByteBuffer.wrap(copyBuf, 0, toRead)
                sourceChannel.position(srcPos)
                val n = sourceChannel.read(bb)
                if (n <= 0) throw IOException("Unexpected EOF copying raw ZIP entry ${entry.name}")
                writeBytes(copyBuf, 0, n)
                srcPos += n
                remaining -= n
            }
        }

        entries += WrittenEntry(
            nameBytes = newNameBytes,
            versionMadeBy = entry.versionMadeBy,
            versionNeeded = versionNeeded,
            gpbf = gpbf,
            method = entry.method,
            dosTime = entry.dosTime,
            dosDate = entry.dosDate,
            crc32 = entry.crc32,
            compressedSize = entry.compressedSize,
            uncompressedSize = entry.uncompressedSize,
            externalAttributes = entry.externalAttributes,
            localHeaderOffset = localHeaderOffset,
            aesExtra = aesExtra,
            timeExtra = timeExtra
        )
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        try {
            writeCentralDirectoryAndEocd()
            flushBuffer()
        } finally {
            channel.close()
        }
    }

    @Throws(IOException::class)
    private fun writeCentralDirectoryAndEocd() {
        val cdOffset = currentPosition
        for (entry in entries) {
            val needUncomp64 = entry.uncompressedSize >= ZIP64_MAGIC_SIZE
            val needComp64 = entry.compressedSize >= ZIP64_MAGIC_SIZE
            val needOffset64 = entry.localHeaderOffset >= ZIP64_MAGIC_SIZE
            val z64FieldsLen = (if (needUncomp64) 8 else 0) +
                (if (needComp64) 8 else 0) +
                (if (needOffset64) 8 else 0)
            val z64Extra = if (z64FieldsLen > 0) {
                ByteBuffer.allocate(4 + z64FieldsLen).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(EXTRA_ZIP64.toShort())
                    putShort(z64FieldsLen.toShort())
                    if (needUncomp64) putLong(entry.uncompressedSize)
                    if (needComp64) putLong(entry.compressedSize)
                    if (needOffset64) putLong(entry.localHeaderOffset)
                }.array()
            } else {
                EMPTY_BYTES
            }
            val cdExtra = z64Extra + entry.aesExtra + entry.timeExtra

            val cd = ByteBuffer.allocate(46 + entry.nameBytes.size + cdExtra.size)
                .order(ByteOrder.LITTLE_ENDIAN)
            cd.putInt(SIG_CENTRAL_DIR_HEADER)
            cd.putShort(entry.versionMadeBy.toShort())
            cd.putShort(entry.versionNeeded.toShort())
            cd.putShort(entry.gpbf.toShort())
            cd.putShort(entry.method.toShort())
            cd.putShort(entry.dosTime.toShort())
            cd.putShort(entry.dosDate.toShort())
            cd.putInt(entry.crc32.toInt())
            cd.putInt(if (needComp64) ZIP64_MAGIC_SIZE.toInt() else entry.compressedSize.toInt())
            cd.putInt(if (needUncomp64) ZIP64_MAGIC_SIZE.toInt() else entry.uncompressedSize.toInt())
            cd.putShort(entry.nameBytes.size.toShort())
            cd.putShort(cdExtra.size.toShort())
            cd.putShort(0) // comment len
            cd.putShort(0) // disk number start
            cd.putShort(0) // internal file attributes
            cd.putInt(entry.externalAttributes)
            cd.putInt(if (needOffset64) ZIP64_MAGIC_SIZE.toInt() else entry.localHeaderOffset.toInt())
            cd.put(entry.nameBytes)
            cd.put(cdExtra)
            writeBytes(cd.array(), 0, cd.capacity())
        }
        val cdSize = currentPosition - cdOffset
        val count = entries.size.toLong()
        val needsZip64Eocd = count >= 0xFFFFL || cdSize >= ZIP64_MAGIC_SIZE || cdOffset >= ZIP64_MAGIC_SIZE

        if (needsZip64Eocd) {
            val z64EocdOffset = currentPosition
            val z64Eocd = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            z64Eocd.putInt(SIG_ZIP64_EOCD)
            z64Eocd.putLong(44L)
            z64Eocd.putShort(((MADE_BY_UNIX shl 8) or 45).toShort())
            z64Eocd.putShort(45.toShort())
            z64Eocd.putInt(0)
            z64Eocd.putInt(0)
            z64Eocd.putLong(count)
            z64Eocd.putLong(count)
            z64Eocd.putLong(cdSize)
            z64Eocd.putLong(cdOffset)
            writeBytes(z64Eocd.array(), 0, 56)

            val z64Loc = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            z64Loc.putInt(SIG_ZIP64_EOCD_LOCATOR)
            z64Loc.putInt(0)
            z64Loc.putLong(z64EocdOffset)
            z64Loc.putInt(1)
            writeBytes(z64Loc.array(), 0, 20)
        }

        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(SIG_EOCD)
        eocd.putShort(0)
        eocd.putShort(0)
        eocd.putShort((if (count >= 0xFFFFL) 0xFFFF else count.toInt()).toShort())
        eocd.putShort((if (count >= 0xFFFFL) 0xFFFF else count.toInt()).toShort())
        eocd.putInt((if (cdSize >= ZIP64_MAGIC_SIZE) ZIP64_MAGIC_SIZE else cdSize).toInt())
        eocd.putInt((if (cdOffset >= ZIP64_MAGIC_SIZE) ZIP64_MAGIC_SIZE else cdOffset).toInt())
        eocd.putShort(0)
        writeBytes(eocd.array(), 0, 22)
    }

    @Throws(IOException::class)
    private fun writeBytes(b: ByteArray, off: Int, len: Int) {
        var remaining = len
        var srcOff = off
        while (remaining > 0) {
            val space = writeBuffer.size - writeBufferPos
            if (space == 0) {
                flushBuffer()
            }
            val chunk = min(remaining, writeBuffer.size - writeBufferPos)
            System.arraycopy(b, srcOff, writeBuffer, writeBufferPos, chunk)
            writeBufferPos += chunk
            srcOff += chunk
            remaining -= chunk
            currentPosition += chunk
        }
    }

    @Throws(IOException::class)
    private fun flushBuffer() {
        if (writeBufferPos == 0) return
        val bb = ByteBuffer.wrap(writeBuffer, 0, writeBufferPos)
        while (bb.hasRemaining()) {
            channel.write(bb)
        }
        writeBufferPos = 0
    }

    private fun normalizeDirName(name: String): String {
        val trimmed = name.trimStart('/').trimEnd('/')
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }

    private fun buildAesExtra(
        strength: Int,
        actualMethod: Int,
        version: Int = WinZipAes.VERSION_AE2
    ): ByteArray =
        ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(WinZipAes.EXTRA_HEADER_ID.toShort())
            putShort(7.toShort())
            putShort(version.toShort())
            put('A'.code.toByte())
            put('E'.code.toByte())
            put(strength.toByte())
            putShort(actualMethod.toShort())
        }.array()

    private fun buildExtendedTimestampExtra(mtimeSeconds: Long?): ByteArray {
        if (mtimeSeconds == null) return EMPTY_BYTES
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(EXTRA_EXTENDED_TIMESTAMP.toShort())
            putShort(5.toShort())
            put(0x01.toByte())
            putInt(mtimeSeconds.toInt())
        }.array()
    }

    private fun toDosTimeDate(fileTime: FileTime?): Pair<Int, Int> {
        if (fileTime == null) return 0 to ((1 shl 5) or 1) // 1980-01-01
        val cal = GregorianCalendar(TimeZone.getDefault()).apply {
            timeInMillis = fileTime.toMillis()
        }
        val year = (cal.get(GregorianCalendar.YEAR) - 1980).coerceIn(0, 127)
        val month = cal.get(GregorianCalendar.MONTH) + 1
        val day = cal.get(GregorianCalendar.DAY_OF_MONTH)
        val hour = cal.get(GregorianCalendar.HOUR_OF_DAY)
        val minute = cal.get(GregorianCalendar.MINUTE)
        val second = cal.get(GregorianCalendar.SECOND) ushr 1
        val dosTime = (hour shl 11) or (minute shl 5) or second
        val dosDate = (year shl 9) or (month shl 5) or day
        return dosTime to dosDate
    }

    private class WrittenEntry(
        val nameBytes: ByteArray,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val gpbf: Int,
        val method: Int,
        val dosTime: Int,
        val dosDate: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val externalAttributes: Int,
        val localHeaderOffset: Long,
        val aesExtra: ByteArray,
        val timeExtra: ByteArray
    )

    companion object {
        private const val SIG_LOCAL_FILE_HEADER = 0x04034b50
        private const val SIG_CENTRAL_DIR_HEADER = 0x02014b50
        private const val SIG_EOCD = 0x06054b50
        private const val SIG_ZIP64_EOCD = 0x06064b50
        private const val SIG_ZIP64_EOCD_LOCATOR = 0x07064b50

        private const val GPBF_ENCRYPTED = 0x0001
        private const val GPBF_DATA_DESCRIPTOR = 0x0008
        private const val GPBF_UTF8 = 0x0800

        private const val EXTRA_ZIP64 = 0x0001
        private const val EXTRA_EXTENDED_TIMESTAMP = 0x5455

        private const val MADE_BY_UNIX = 3
        private const val ZIP64_MAGIC_SIZE = 0xFFFFFFFFL
        private const val IO_BUFFER_SIZE = 64 * 1024
        private val EMPTY_BYTES = ByteArray(0)
    }
}
