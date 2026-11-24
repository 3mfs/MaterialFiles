/*
 * Copyright (c) 2026 MaterialFilesExtended Contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.zip

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.archive.archiver.ReadArchive
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import kotlin.math.min

/**
 * Pure-JVM Central-Directory ZIP reader operating directly on java8.nio.channels.SeekableByteChannel.
 * Gives O(1) entry listing and O(1) random-access entry streaming/seeking on API 23+.
 */
internal object FastZipFile {
    private const val SIG_LOCAL_FILE_HEADER = 0x04034b50
    private const val SIG_CENTRAL_DIR_HEADER = 0x02014b50
    private const val SIG_EOCD = 0x06054b50
    private const val SIG_ZIP64_EOCD = 0x06064b50
    private const val SIG_ZIP64_EOCD_LOCATOR = 0x07064b50

    private const val GPBF_ENCRYPTED = 0x0001
    private const val GPBF_DATA_DESCRIPTOR = 0x0008
    private const val GPBF_UTF8 = 0x0800

    private const val EXTRA_ZIP64 = 0x0001
    private const val EXTRA_NTFS = 0x000A
    private const val EXTRA_EXTENDED_TIMESTAMP = 0x5455
    private const val EXTRA_UNICODE_PATH = 0x7075

    private const val METHOD_STORED = ZipEntry.STORED // 0
    private const val METHOD_DEFLATED = ZipEntry.DEFLATED // 8
    private const val METHOD_AES = WinZipAes.METHOD_AES // 99

    private const val MADE_BY_UNIX = 3
    private const val MADE_BY_OSX = 19

    private const val IO_BUFFER_SIZE = 64 * 1024

    data class ZipEntryInfo(
        val name: String,
        val rawNameBytes: ByteArray,
        val isDirectory: Boolean,
        val isEncrypted: Boolean,
        val method: Int,
        val actualMethod: Int,
        val aesVersion: Int,
        val aesStrength: Int,
        val gpbf: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val dosTime: Int,
        val dosDate: Int,
        val lastModifiedTime: FileTime?,
        val lastAccessTime: FileTime?,
        val creationTime: FileTime?,
        val versionMadeBy: Int,
        val externalAttributes: Int,
        val localHeaderOffset: Long,
        val extraField: ByteArray,
        val posixType: PosixFileType,
        val posixMode: Set<PosixFileModeBit>,
        val symbolicLinkTarget: String?
    ) {
        val isFastReadSupported: Boolean
            get() = actualMethod == METHOD_STORED || actualMethod == METHOD_DEFLATED

        fun toReadArchiveEntry(): ReadArchive.Entry =
            ReadArchive.Entry(
                name,
                isEncrypted,
                lastModifiedTime,
                lastAccessTime,
                creationTime,
                posixType,
                uncompressedSize,
                null,
                null,
                posixMode,
                symbolicLinkTarget
            )
    }

    /**
     * Checks whether the channel starts with a ZIP signature (Local File Header or empty EOCD)
     * or ends with an EOCD record.
     */
    @Throws(IOException::class)
    fun isZipChannel(channel: SeekableByteChannel): Boolean {
        val size = channel.size()
        if (size < 22) return false
        channel.position(0)
        val header = ByteBuffer.allocate(4)
        var read = 0
        while (read < 4) {
            val n = channel.read(header)
            if (n <= 0) break
            read += n
        }
        if (read < 4) return false
        header.flip()
        val sig = header.order(ByteOrder.LITTLE_ENDIAN).int
        return sig == SIG_LOCAL_FILE_HEADER || sig == SIG_EOCD
    }

    @Throws(IOException::class)
    fun readEntries(
        channel: SeekableByteChannel,
        fallbackCharset: Charset
    ): List<ZipEntryInfo> {
        val fileSize = channel.size()
        if (fileSize < 22) {
            throw IOException("File too small to be a ZIP archive: $fileSize")
        }

        // Locate EOCD by reading up to 64 KiB + 22 bytes from end of file.
        val searchLen = min(fileSize, 65535L + 22L).toInt()
        val tailStart = fileSize - searchLen
        val tailBytes = readExactAt(channel, tailStart, searchLen)
        val tailBuf = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)

        var eocdLocalPos = -1
        for (i in searchLen - 22 downTo 0) {
            if (tailBytes[i] == 0x50.toByte() &&
                tailBytes[i + 1] == 0x4b.toByte() &&
                tailBytes[i + 2] == 0x05.toByte() &&
                tailBytes[i + 3] == 0x06.toByte()
            ) {
                val commentLen = tailBuf.getShort(i + 20).toInt() and 0xFFFF
                if (i + 22 + commentLen == searchLen) {
                    eocdLocalPos = i
                    break
                }
            }
        }
        if (eocdLocalPos == -1) {
            throw IOException("End of Central Directory record not found")
        }

        val eocdOffset = tailStart + eocdLocalPos
        var entryCount = (tailBuf.getShort(eocdLocalPos + 10).toInt() and 0xFFFF).toLong()
        var cdSize = tailBuf.getInt(eocdLocalPos + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = tailBuf.getInt(eocdLocalPos + 16).toLong() and 0xFFFFFFFFL

        // Check ZIP64 EOCD Locator immediately before EOCD.
        if ((entryCount == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) &&
            eocdOffset >= 20
        ) {
            val locBytes = readExactAt(channel, eocdOffset - 20, 20)
            val locBuf = ByteBuffer.wrap(locBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (locBuf.getInt(0) == SIG_ZIP64_EOCD_LOCATOR) {
                val zip64EocdOffset = locBuf.getLong(8)
                val z64Bytes = readExactAt(channel, zip64EocdOffset, 56)
                val z64Buf = ByteBuffer.wrap(z64Bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (z64Buf.getInt(0) == SIG_ZIP64_EOCD) {
                    entryCount = z64Buf.getLong(32)
                    cdSize = z64Buf.getLong(40)
                    cdOffset = z64Buf.getLong(48)
                }
            }
        }

        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > fileSize) {
            throw IOException("Invalid ZIP Central Directory bounds: offset=$cdOffset size=$cdSize")
        }

        // Read the entire Central Directory in one buffered pass!
        val cdBytes = readExactAt(channel, cdOffset, cdSize.toInt())
        val cdBuf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<ZipEntryInfo>(entryCount.coerceAtMost(65536L).toInt())

        var pos = 0
        while (pos + 46 <= cdBytes.size) {
            val sig = cdBuf.getInt(pos)
            if (sig != SIG_CENTRAL_DIR_HEADER) {
                throw IOException("Expected Central Directory signature at $pos, got 0x${sig.toString(16)}")
            }
            val versionMadeBy = cdBuf.getShort(pos + 4).toInt() and 0xFFFF
            val gpbf = cdBuf.getShort(pos + 8).toInt() and 0xFFFF
            val method = cdBuf.getShort(pos + 10).toInt() and 0xFFFF
            val dosTime = cdBuf.getShort(pos + 12).toInt() and 0xFFFF
            val dosDate = cdBuf.getShort(pos + 14).toInt() and 0xFFFF
            val crc32 = cdBuf.getInt(pos + 16).toLong() and 0xFFFFFFFFL
            var compressedSize = cdBuf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
            var uncompressedSize = cdBuf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cdBuf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = cdBuf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = cdBuf.getShort(pos + 32).toInt() and 0xFFFF
            val externalAttributes = cdBuf.getInt(pos + 38)
            var localHeaderOffset = cdBuf.getInt(pos + 42).toLong() and 0xFFFFFFFFL

            val headerEnd = pos + 46 + nameLen + extraLen + commentLen
            if (headerEnd > cdBytes.size) {
                throw EOFException("Truncated Central Directory entry")
            }

            val rawNameBytes = cdBytes.copyOfRange(pos + 46, pos + 46 + nameLen)
            val extraBytes = if (extraLen > 0) {
                cdBytes.copyOfRange(pos + 46 + nameLen, pos + 46 + nameLen + extraLen)
            } else {
                EMPTY_BYTES
            }

            // Parse extra fields.
            var unicodeName: String? = null
            var extMtime: FileTime? = null
            var extAtime: FileTime? = null
            var extCtime: FileTime? = null
            var aesVersion = 0
            var aesStrength = 0
            var aesActualMethod = method

            var exPos = 0
            val exBuf = ByteBuffer.wrap(extraBytes).order(ByteOrder.LITTLE_ENDIAN)
            while (exPos + 4 <= extraBytes.size) {
                val tag = exBuf.getShort(exPos).toInt() and 0xFFFF
                val size = exBuf.getShort(exPos + 2).toInt() and 0xFFFF
                val dataStart = exPos + 4
                val dataEnd = dataStart + size
                if (dataEnd > extraBytes.size) break
                when (tag) {
                    EXTRA_ZIP64 -> {
                        var zPos = dataStart
                        if (uncompressedSize == 0xFFFFFFFFL && zPos + 8 <= dataEnd) {
                            uncompressedSize = exBuf.getLong(zPos)
                            zPos += 8
                        }
                        if (compressedSize == 0xFFFFFFFFL && zPos + 8 <= dataEnd) {
                            compressedSize = exBuf.getLong(zPos)
                            zPos += 8
                        }
                        if (localHeaderOffset == 0xFFFFFFFFL && zPos + 8 <= dataEnd) {
                            localHeaderOffset = exBuf.getLong(zPos)
                        }
                    }
                    EXTRA_UNICODE_PATH -> {
                        if (size >= 5 && extraBytes[dataStart].toInt() == 1) {
                            val expectedCrc = exBuf.getInt(dataStart + 1).toLong() and 0xFFFFFFFFL
                            val actualCrc = java.util.zip.CRC32().apply { update(rawNameBytes) }.value
                            if (expectedCrc == actualCrc) {
                                unicodeName = String(
                                    extraBytes, dataStart + 5, size - 5, StandardCharsets.UTF_8
                                )
                            }
                        }
                    }
                    EXTRA_EXTENDED_TIMESTAMP -> {
                        if (size >= 1) {
                            val flags = extraBytes[dataStart].toInt()
                            var tPos = dataStart + 1
                            if ((flags and 0x01) != 0 && tPos + 4 <= dataEnd) {
                                extMtime = FileTime.from(Instant.ofEpochSecond(exBuf.getInt(tPos).toLong()))
                                tPos += 4
                            }
                            if ((flags and 0x02) != 0 && tPos + 4 <= dataEnd) {
                                extAtime = FileTime.from(Instant.ofEpochSecond(exBuf.getInt(tPos).toLong()))
                                tPos += 4
                            }
                            if ((flags and 0x04) != 0 && tPos + 4 <= dataEnd) {
                                extCtime = FileTime.from(Instant.ofEpochSecond(exBuf.getInt(tPos).toLong()))
                            }
                        }
                    }
                    EXTRA_NTFS -> {
                        var nPos = dataStart + 4 // skip 4 reserved bytes
                        while (nPos + 4 <= dataEnd) {
                            val attrTag = exBuf.getShort(nPos).toInt() and 0xFFFF
                            val attrSize = exBuf.getShort(nPos + 2).toInt() and 0xFFFF
                            nPos += 4
                            if (attrTag == 0x0001 && attrSize == 24 && nPos + 24 <= dataEnd) {
                                extMtime = extMtime ?: ntfsToFileTime(exBuf.getLong(nPos))
                                extAtime = extAtime ?: ntfsToFileTime(exBuf.getLong(nPos + 8))
                                extCtime = extCtime ?: ntfsToFileTime(exBuf.getLong(nPos + 16))
                                break
                            }
                            nPos += attrSize
                        }
                    }
                    WinZipAes.EXTRA_HEADER_ID -> {
                        if (size >= 7) {
                            aesVersion = exBuf.getShort(dataStart).toInt() and 0xFFFF
                            aesStrength = extraBytes[dataStart + 4].toInt() and 0xFF
                            aesActualMethod = exBuf.getShort(dataStart + 5).toInt() and 0xFFFF
                        }
                    }
                }
                exPos = dataEnd
            }

            val charset = if ((gpbf and GPBF_UTF8) != 0) StandardCharsets.UTF_8 else fallbackCharset
            val rawName = (unicodeName ?: String(rawNameBytes, charset))
                .replace('\\', '/')
                .trimStart('/')
            val isNameDir = rawName.endsWith("/")
            val normalizedName = rawName.trimEnd('/')

            val madeByOs = versionMadeBy ushr 8
            val unixMode = if (madeByOs == MADE_BY_UNIX || madeByOs == MADE_BY_OSX) {
                (externalAttributes ushr 16) and 0xFFFF
            } else {
                0
            }
            val isDirAttr = isNameDir || (unixMode == 0 && (externalAttributes and 0x10) != 0)
            val posixType = when {
                unixMode != 0 -> PosixFileType.fromMode(unixMode)
                isDirAttr -> PosixFileType.DIRECTORY
                else -> PosixFileType.REGULAR_FILE
            }
            val isDirectory = posixType == PosixFileType.DIRECTORY
            val posixMode = if (unixMode != 0) {
                PosixFileMode.fromInt(unixMode)
            } else if (isDirectory) {
                PosixFileMode.DIRECTORY_DEFAULT
            } else {
                PosixFileMode.FILE_DEFAULT
            }

            val isEncrypted = (gpbf and GPBF_ENCRYPTED) != 0
            val actualMethod = if (method == METHOD_AES) aesActualMethod else method
            val mtime = extMtime ?: dosToFileTime(dosTime, dosDate)

            val entry = ZipEntryInfo(
                name = normalizedName,
                rawNameBytes = rawNameBytes,
                isDirectory = isDirectory,
                isEncrypted = isEncrypted,
                method = method,
                actualMethod = actualMethod,
                aesVersion = aesVersion,
                aesStrength = aesStrength,
                gpbf = gpbf,
                crc32 = crc32,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                dosTime = dosTime,
                dosDate = dosDate,
                lastModifiedTime = mtime,
                lastAccessTime = extAtime,
                creationTime = extCtime,
                versionMadeBy = versionMadeBy,
                externalAttributes = externalAttributes,
                localHeaderOffset = localHeaderOffset,
                extraField = extraBytes,
                posixType = posixType,
                posixMode = posixMode,
                symbolicLinkTarget = null
            )

            // Read small unencrypted symlink targets directly so readSymbolicLink works cleanly.
            val finalEntry = if (posixType == PosixFileType.SYMBOLIC_LINK &&
                !isEncrypted &&
                entry.isFastReadSupported &&
                uncompressedSize in 1..4096
            ) {
                val target = try {
                    openEntryInputStream(channel, entry, emptyList(), closeChannelOnClose = false, fallbackCharset)
                        .use { it.readBytes().toString(StandardCharsets.UTF_8) }
                } catch (_: Exception) {
                    null
                }
                entry.copy(symbolicLinkTarget = target)
            } else {
                entry
            }

            if (normalizedName.isNotEmpty() || isDirectory) {
                entries += finalEntry
            }
            pos = headerEnd
        }
        return entries
    }

    @Throws(IOException::class)
    fun openEntryInputStream(
        channel: SeekableByteChannel,
        entry: ZipEntryInfo,
        passwords: List<String>,
        closeChannelOnClose: Boolean,
        fallbackCharset: Charset
    ): InputStream {
        var success = false
        try {
            val lfh = readExactAt(channel, entry.localHeaderOffset, 30)
            val lfhBuf = ByteBuffer.wrap(lfh).order(ByteOrder.LITTLE_ENDIAN)
            if (lfhBuf.getInt(0) != SIG_LOCAL_FILE_HEADER) {
                throw IOException("Invalid Local File Header at ${entry.localHeaderOffset}")
            }
            val localNameLen = lfhBuf.getShort(26).toInt() and 0xFFFF
            val localExtraLen = lfhBuf.getShort(28).toInt() and 0xFFFF
            val dataOffset = entry.localHeaderOffset + 30L + localNameLen + localExtraLen

            val baseStream: InputStream = if (entry.isEncrypted) {
                when (entry.method) {
                    METHOD_AES -> openAesStream(channel, dataOffset, entry, passwords, closeChannelOnClose)
                    else -> openZipCryptoStream(
                        channel, dataOffset, entry, passwords, closeChannelOnClose, fallbackCharset
                    )
                }
            } else {
                BoundedChannelInputStream(channel, dataOffset, entry.compressedSize, closeChannelOnClose)
            }

            val result = when (entry.actualMethod) {
                METHOD_STORED -> baseStream
                METHOD_DEFLATED -> {
                    val inflater = Inflater(true)
                    object : InflaterInputStream(baseStream, inflater, IO_BUFFER_SIZE) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                inflater.end()
                            }
                        }
                    }
                }
                else -> throw IOException("Unsupported ZIP compression method: ${entry.actualMethod}")
            }
            success = true
            return result
        } finally {
            if (!success && closeChannelOnClose) {
                try {
                    channel.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun openAesStream(
        channel: SeekableByteChannel,
        dataOffset: Long,
        entry: ZipEntryInfo,
        passwords: List<String>,
        closeChannelOnClose: Boolean
    ): InputStream {
        if (passwords.isEmpty()) {
            throw ArchiveException(Archive.ERRNO_MISC, "Passphrase required for this entry")
        }
        val saltSize = WinZipAes.saltSize(entry.aesStrength)
        val headerSize = saltSize + WinZipAes.PASSWORD_VERIFIER_SIZE
        val overhead = headerSize + WinZipAes.AUTH_CODE_SIZE
        if (entry.compressedSize < overhead) {
            throw IOException("Corrupt WinZip AES entry payload")
        }
        val header = readExactAt(channel, dataOffset, headerSize)
        val salt = header.copyOfRange(0, saltSize)
        val verifier = header.copyOfRange(saltSize, headerSize)

        var decryptor: WinZipAes.Decryptor? = null
        for (password in passwords) {
            decryptor = WinZipAes.tryCreateDecryptor(password, salt, verifier, entry.aesStrength)
            if (decryptor != null) break
        }
        if (decryptor == null) {
            throw ArchiveException(Archive.ERRNO_MISC, "Incorrect passphrase")
        }

        val cipherDataOffset = dataOffset + headerSize
        val cipherDataLen = entry.compressedSize - overhead
        val authCodeOffset = cipherDataOffset + cipherDataLen
        val bounded = BoundedChannelInputStream(
            channel, cipherDataOffset, cipherDataLen, closeChannelOnClose = false
        )
        return WinZipAesInputStream(
            bounded, decryptor, channel, authCodeOffset, closeChannelOnClose
        )
    }

    @Throws(IOException::class)
    private fun openZipCryptoStream(
        channel: SeekableByteChannel,
        dataOffset: Long,
        entry: ZipEntryInfo,
        passwords: List<String>,
        closeChannelOnClose: Boolean,
        fallbackCharset: Charset
    ): InputStream {
        if (passwords.isEmpty()) {
            throw ArchiveException(Archive.ERRNO_MISC, "Passphrase required for this entry")
        }
        if (entry.compressedSize < ZipCryptoDecryptor.HEADER_SIZE) {
            throw IOException("Corrupt ZipCrypto entry payload")
        }
        val header = readExactAt(channel, dataOffset, ZipCryptoDecryptor.HEADER_SIZE)
        val hasDataDescriptor = (entry.gpbf and GPBF_DATA_DESCRIPTOR) != 0
        val charset = if ((entry.gpbf and GPBF_UTF8) != 0) StandardCharsets.UTF_8 else fallbackCharset

        var decryptor: ZipCryptoDecryptor? = null
        for (password in passwords) {
            decryptor = ZipCryptoDecryptor.tryCreate(
                password, charset, header, entry.crc32, entry.dosTime, hasDataDescriptor
            )
            if (decryptor != null) break
        }
        if (decryptor == null) {
            throw ArchiveException(Archive.ERRNO_MISC, "Incorrect passphrase")
        }

        val payloadOffset = dataOffset + ZipCryptoDecryptor.HEADER_SIZE
        val payloadLen = entry.compressedSize - ZipCryptoDecryptor.HEADER_SIZE
        val bounded = BoundedChannelInputStream(
            channel, payloadOffset, payloadLen, closeChannelOnClose
        )
        return object : InputStream() {
            private val oneByte = ByteArray(1)

            override fun read(): Int {
                val n = read(oneByte, 0, 1)
                return if (n == -1) -1 else oneByte[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = bounded.read(b, off, len)
                if (n > 0) {
                    decryptor.decrypt(b, off, n)
                }
                return n
            }

            override fun close() {
                bounded.close()
            }
        }
    }

    @Throws(IOException::class)
    fun getEntryDataRange(
        channel: SeekableByteChannel,
        entry: ZipEntryInfo
    ): Pair<Long, Long> {
        val lfh = readExactAt(channel, entry.localHeaderOffset, 30)
        val lfhBuf = ByteBuffer.wrap(lfh).order(ByteOrder.LITTLE_ENDIAN)
        if (lfhBuf.getInt(0) != SIG_LOCAL_FILE_HEADER) {
            throw IOException("Invalid Local File Header at ${entry.localHeaderOffset}")
        }
        val localNameLen = lfhBuf.getShort(26).toInt() and 0xFFFF
        val localExtraLen = lfhBuf.getShort(28).toInt() and 0xFFFF
        val dataOffset = entry.localHeaderOffset + 30L + localNameLen + localExtraLen
        return dataOffset to entry.compressedSize
    }

    @Throws(IOException::class)
    private fun readExactAt(channel: SeekableByteChannel, offset: Long, length: Int): ByteArray {
        if (length == 0) return EMPTY_BYTES
        val result = ByteArray(length)
        val buffer = ByteBuffer.wrap(result)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer)
            if (n <= 0) {
                throw EOFException("Unexpected EOF at offset=$offset (read=${buffer.position()}/$length)")
            }
        }
        return result
    }

    private fun ntfsToFileTime(hundredNanosSince1601: Long): FileTime {
        val unixEpochDiff100Ns = 116444736000000000L
        val nanosSince1970 = (hundredNanosSince1601 - unixEpochDiff100Ns) * 100L
        val seconds = Math.floorDiv(nanosSince1970, 1_000_000_000L)
        val nanoAdj = Math.floorMod(nanosSince1970, 1_000_000_000L)
        return FileTime.from(Instant.ofEpochSecond(seconds, nanoAdj))
    }

    private fun dosToFileTime(dosTime: Int, dosDate: Int): FileTime? {
        if (dosTime == 0 && dosDate == 0) return null
        val second = (dosTime and 0x1F) shl 1
        val minute = (dosTime ushr 5) and 0x3F
        val hour = (dosTime ushr 11) and 0x1F
        val day = dosDate and 0x1F
        val month = ((dosDate ushr 5) and 0x0F) - 1
        val year = ((dosDate ushr 9) and 0x7F) + 1980
        val cal = GregorianCalendar(TimeZone.getDefault()).apply {
            clear()
            set(year, month, day, hour, minute, second)
        }
        return FileTime.fromMillis(cal.timeInMillis)
    }

    private val EMPTY_BYTES = ByteArray(0)

    private class BoundedChannelInputStream(
        private val channel: SeekableByteChannel,
        startOffset: Long,
        length: Long,
        private val closeChannelOnClose: Boolean
    ) : InputStream() {
        private var pos = startOffset
        private var remaining = length
        private val buffer = ByteArray(IO_BUFFER_SIZE)
        private var bufPos = 0
        private var bufLimit = 0

        override fun read(): Int {
            if (bufPos >= bufLimit) {
                if (!fillBuffer()) return -1
            }
            return buffer[bufPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bufPos >= bufLimit) {
                if (len >= IO_BUFFER_SIZE) {
                    if (remaining <= 0) return -1
                    val toRead = min(len.toLong(), remaining).toInt()
                    val bb = ByteBuffer.wrap(b, off, toRead)
                    channel.position(pos)
                    val n = channel.read(bb)
                    if (n <= 0) return -1
                    pos += n
                    remaining -= n
                    return n
                }
                if (!fillBuffer()) return -1
            }
            val avail = bufLimit - bufPos
            val copy = min(len, avail)
            System.arraycopy(buffer, bufPos, b, off, copy)
            bufPos += copy
            return copy
        }

        private fun fillBuffer(): Boolean {
            if (remaining <= 0) return false
            val toRead = min(buffer.size.toLong(), remaining).toInt()
            val bb = ByteBuffer.wrap(buffer, 0, toRead)
            channel.position(pos)
            val n = channel.read(bb)
            if (n <= 0) {
                remaining = 0
                return false
            }
            pos += n
            remaining -= n
            bufPos = 0
            bufLimit = n
            return true
        }

        override fun close() {
            if (closeChannelOnClose) {
                channel.close()
            }
        }
    }

    private class WinZipAesInputStream(
        private val upstream: InputStream,
        private val decryptor: WinZipAes.Decryptor,
        private val channel: SeekableByteChannel,
        private val authCodeOffset: Long,
        private val closeChannelOnClose: Boolean
    ) : InputStream() {
        private val oneByte = ByteArray(1)
        private var authVerified = false
        private var closed = false

        override fun read(): Int {
            val n = read(oneByte, 0, 1)
            return if (n == -1) -1 else oneByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = upstream.read(b, off, len)
            if (n > 0) {
                decryptor.decrypt(b, off, n)
            } else if (n == -1 && !authVerified) {
                authVerified = true
                val expectedAuth = readExactAt(channel, authCodeOffset, WinZipAes.AUTH_CODE_SIZE)
                if (!decryptor.verifyAuthCode(expectedAuth)) {
                    throw IOException("WinZip AES authentication failed (corrupt data or wrong password)")
                }
            }
            return n
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                upstream.close()
            } finally {
                decryptor.close()
                if (closeChannelOnClose) {
                    channel.close()
                }
            }
        }
    }
}
