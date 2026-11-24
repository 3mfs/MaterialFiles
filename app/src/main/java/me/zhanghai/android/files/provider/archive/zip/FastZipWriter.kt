/*
 * Copyright (c) 2026 MaterialFilesExtended Contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.zip

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.archive.archiver.ArchiveCompressionLevel
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.asByteStringListPath
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.provider.linux.syscall.Syscall
import me.zhanghai.android.files.provider.root.isRunningAsRoot
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import kotlin.math.min

/**
 * High-throughput multi-threaded ZIP writer backed by native zlib (via JNI) with buffered
 * sequential I/O, zero-stall directory/symlink queueing, and in-buffer Local File Header patching.
 */
internal class FastZipWriter(
    private val channel: SeekableByteChannel,
    private val compressionLevel: ArchiveCompressionLevel,
    private val password: String?
) : Closeable {
    private val entries = mutableListOf<WrittenEntry>()
    private val writeBuffer = ByteArray(WRITE_BUFFER_SIZE)
    private var writeBufferPos = 0
    private var bufferStartPosition = 0L
    private var currentPosition = 0L
    private var closed = false

    private val dosCalendar = GregorianCalendar(TimeZone.getDefault())
    private val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val executor: ExecutorService = Executors.newFixedThreadPool(workerCount)
    private val deflaterPool = ConcurrentLinkedQueue<Deflater>()
    private val blockBufferPool = ConcurrentLinkedQueue<ByteArray>()
    private val pendingEntries = ArrayDeque<PendingEntry>()
    private var pendingBytes = 0L

    @Throws(IOException::class)
    fun writeDirectory(
        entryName: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>
    ) {
        val normalized = normalizeDirName(entryName)
        if (pendingEntries.isNotEmpty()) {
            val prepared = PreparedEntry(
                nameBytes = normalized.toByteArray(StandardCharsets.UTF_8),
                isDirectory = true,
                isSymbolicLink = false,
                lastModifiedTime = lastModifiedTime,
                mode = mode,
                uncompressedSize = 0L,
                compressedSize = 0L,
                crc32 = 0L,
                salt = null,
                verifier = null,
                payloadBytes = EMPTY_BYTES,
                payloadLen = 0,
                authCode = null
            )
            pendingEntries.addLast(PendingEntry(CompletedFuture(prepared), 0L, null))
            return
        }
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
        if (pendingEntries.isNotEmpty()) {
            val cleanName = entryName.trimStart('/')
            val useAes = password != null
            val actualMethod = if (compressionLevel == ArchiveCompressionLevel.STORE) {
                ZipEntry.STORED
            } else {
                ZipEntry.DEFLATED
            }
            val future = executor.submit(Callable {
                prepareEntryFromBytes(
                    name = cleanName,
                    isSymbolicLink = true,
                    lastModifiedTime = lastModifiedTime,
                    mode = mode,
                    rawBytes = bytes,
                    rawLen = bytes.size,
                    useAes = useAes,
                    actualMethod = actualMethod
                )
            })
            pendingEntries.addLast(PendingEntry(future, bytes.size.toLong(), null))
            pendingBytes += bytes.size
            return
        }
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
        drainAllPendingEntries()
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
    fun writeFileFromPath(
        entryName: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>,
        uncompressedSizeHint: Long,
        sourcePath: Path,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ) {
        while (pendingEntries.isNotEmpty() && pendingEntries.first().future.isDone) {
            drainOnePendingEntry()
        }
        if (uncompressedSizeHint in 1..PARALLEL_ENTRY_MAX_SIZE) {
            while (pendingEntries.size >= workerCount * 4 || pendingBytes >= MAX_PENDING_BYTES) {
                drainOnePendingEntry()
            }
            val cleanName = entryName.trimStart('/')
            val useAes = password != null
            val actualMethod = if (compressionLevel == ArchiveCompressionLevel.STORE) {
                ZipEntry.STORED
            } else {
                ZipEntry.DEFLATED
            }
            val future = executor.submit(Callable {
                prepareEntryFromPath(
                    name = cleanName,
                    lastModifiedTime = lastModifiedTime,
                    mode = mode,
                    sizeHint = uncompressedSizeHint,
                    sourcePath = sourcePath,
                    useAes = useAes,
                    actualMethod = actualMethod
                )
            })
            pendingEntries.addLast(PendingEntry(future, uncompressedSizeHint, listener))
            pendingBytes += uncompressedSizeHint
        } else {
            drainAllPendingEntries()
            sourcePath.newInputStream(LinkOption.NOFOLLOW_LINKS).use { inputStream ->
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
        }
    }

    @Throws(IOException::class)
    private fun prepareEntryFromPath(
        name: String,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>,
        sizeHint: Long,
        sourcePath: Path,
        useAes: Boolean,
        actualMethod: Int
    ): PreparedEntry {
        if (sourcePath.isLinuxPath && !isRunningAsRoot) {
            val nativeLevel = if (actualMethod == ZipEntry.STORED) 0 else compressionLevel.zipLevel
            val crcAndSize = LongArray(2)
            val nativeBytes = Syscall.nativeDeflateFileToBuffer(
                sourcePath.asByteStringListPath().toByteString(),
                nativeLevel,
                crcAndSize
            )
            if (nativeBytes != null) {
                val rawLen = crcAndSize[1]
                val rawCrc = crcAndSize[0] and 0xFFFFFFFFL
                val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
                var salt: ByteArray? = null
                var verifier: ByteArray? = null
                var authCode: ByteArray? = null
                var compressedSize = nativeBytes.size.toLong()
                var finalCrc = rawCrc
                if (useAes) {
                    val encryptor = WinZipAes.createEncryptor(password!!, WinZipAes.STRENGTH_256)
                    try {
                        salt = encryptor.salt
                        verifier = encryptor.verifier
                        if (nativeBytes.isNotEmpty()) {
                            encryptor.encrypt(nativeBytes, 0, nativeBytes.size)
                        }
                        authCode = encryptor.finishAuthCode()
                        compressedSize += salt.size + verifier.size + authCode.size
                        finalCrc = 0L
                    } finally {
                        encryptor.close()
                    }
                }
                return PreparedEntry(
                    nameBytes = nameBytes,
                    isDirectory = false,
                    isSymbolicLink = false,
                    lastModifiedTime = lastModifiedTime,
                    mode = mode,
                    uncompressedSize = rawLen,
                    compressedSize = compressedSize,
                    crc32 = finalCrc,
                    salt = salt,
                    verifier = verifier,
                    payloadBytes = nativeBytes,
                    payloadLen = nativeBytes.size,
                    authCode = authCode
                )
            }
        }

        val rawBytes: ByteArray
        val rawLen: Int
        sourcePath.newInputStream(LinkOption.NOFOLLOW_LINKS).use { input ->
            val initialCap = (sizeHint.toInt() + 1).coerceAtLeast(4096)
            var buf = ByteArray(initialCap)
            var pos = 0
            while (true) {
                if (pos == buf.size) {
                    buf = buf.copyOf(buf.size * 2)
                }
                val n = input.read(buf, pos, buf.size - pos)
                if (n == -1) break
                pos += n
                if (Thread.interrupted()) throw InterruptedIOException()
            }
            rawBytes = buf
            rawLen = pos
        }
        return prepareEntryFromBytes(
            name = name,
            isSymbolicLink = false,
            lastModifiedTime = lastModifiedTime,
            mode = mode,
            rawBytes = rawBytes,
            rawLen = rawLen,
            useAes = useAes,
            actualMethod = actualMethod
        )
    }

    private fun prepareEntryFromBytes(
        name: String,
        isSymbolicLink: Boolean,
        lastModifiedTime: FileTime?,
        mode: Set<PosixFileModeBit>,
        rawBytes: ByteArray,
        rawLen: Int,
        useAes: Boolean,
        actualMethod: Int
    ): PreparedEntry {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        var crcValue = 0L
        if (!useAes) {
            val crc = CRC32()
            crc.update(rawBytes, 0, rawLen)
            crcValue = crc.value
        }

        val payloadBytes: ByteArray
        val payloadLen: Int
        if (actualMethod == ZipEntry.STORED) {
            payloadBytes = rawBytes
            payloadLen = rawLen
        } else {
            val outCrc = LongArray(1)
            val nativeCompressed = Syscall.nativeDeflateBlock(
                rawBytes, rawLen, compressionLevel.zipLevel, true, outCrc
            )
            if (nativeCompressed != null) {
                payloadBytes = nativeCompressed
                payloadLen = nativeCompressed.size
                if (!useAes) {
                    crcValue = outCrc[0] and 0xFFFFFFFFL
                }
            } else {
                val deflater = acquireDeflater()
                try {
                    val outStream = ByteArrayOutputStream(minOf(rawLen, 64 * 1024).coerceAtLeast(256))
                    val deflateBuf = ByteArray(IO_BUFFER_SIZE)
                    deflater.setInput(rawBytes, 0, rawLen)
                    deflater.finish()
                    while (!deflater.finished()) {
                        val c = deflater.deflate(deflateBuf)
                        if (c > 0) outStream.write(deflateBuf, 0, c)
                    }
                    payloadBytes = outStream.toByteArray()
                    payloadLen = payloadBytes.size
                } finally {
                    releaseDeflater(deflater)
                }
            }
        }

        var salt: ByteArray? = null
        var verifier: ByteArray? = null
        var authCode: ByteArray? = null
        var compressedSize = payloadLen.toLong()
        if (useAes) {
            val encryptor = WinZipAes.createEncryptor(password!!, WinZipAes.STRENGTH_256)
            try {
                salt = encryptor.salt
                verifier = encryptor.verifier
                if (payloadLen > 0) {
                    encryptor.encrypt(payloadBytes, 0, payloadLen)
                }
                authCode = encryptor.finishAuthCode()
                compressedSize += salt.size + verifier.size + authCode.size
            } finally {
                encryptor.close()
            }
        }

        return PreparedEntry(
            nameBytes = nameBytes,
            isDirectory = false,
            isSymbolicLink = isSymbolicLink,
            lastModifiedTime = lastModifiedTime,
            mode = mode,
            uncompressedSize = rawLen.toLong(),
            compressedSize = compressedSize,
            crc32 = crcValue,
            salt = salt,
            verifier = verifier,
            payloadBytes = payloadBytes,
            payloadLen = payloadLen,
            authCode = authCode
        )
    }

    @Throws(IOException::class)
    private fun drainOnePendingEntry() {
        val pending = pendingEntries.pollFirst() ?: return
        pendingBytes = (pendingBytes - pending.sizeHint).coerceAtLeast(0L)
        val prepared = try {
            pending.future.get()
        } catch (e: InterruptedException) {
            cancelPendingEntries()
            Thread.currentThread().interrupt()
            throw InterruptedIOException().apply { initCause(e) }
        } catch (e: ExecutionException) {
            cancelPendingEntries()
            val cause = e.cause
            if (cause is IOException) throw cause
            throw IOException(cause)
        }
        writePreparedEntry(prepared)
        pending.listener?.invoke(prepared.uncompressedSize)
    }

    @Throws(IOException::class)
    private fun drainAllPendingEntries() {
        while (pendingEntries.isNotEmpty()) {
            drainOnePendingEntry()
        }
    }

    private fun cancelPendingEntries() {
        while (pendingEntries.isNotEmpty()) {
            pendingEntries.pollFirst()?.future?.cancel(true)
        }
        pendingBytes = 0L
    }

    @Throws(IOException::class)
    private fun writePreparedEntry(prepared: PreparedEntry) {
        val useAes = prepared.salt != null
        val actualMethod = if (prepared.isDirectory || compressionLevel == ArchiveCompressionLevel.STORE) {
            ZipEntry.STORED
        } else {
            ZipEntry.DEFLATED
        }
        val headerMethod = if (useAes) WinZipAes.METHOD_AES else actualMethod
        val gpbf = GPBF_UTF8 or (if (useAes) GPBF_ENCRYPTED else 0)
        val (dosTime, dosDate) = toDosTimeDate(prepared.lastModifiedTime)
        val mtimeSeconds = prepared.lastModifiedTime?.toInstant()?.epochSecond
        val posixType = when {
            prepared.isDirectory -> PosixFileType.DIRECTORY
            prepared.isSymbolicLink -> PosixFileType.SYMBOLIC_LINK
            else -> PosixFileType.REGULAR_FILE
        }
        val fullMode = posixType.mode or prepared.mode.toInt()
        val extAttr = (fullMode shl 16) or (if (prepared.isDirectory) 0x10 else 0)
        val versionMadeBy = (MADE_BY_UNIX shl 8) or 45

        val needsZip64Sizes = prepared.uncompressedSize >= ZIP64_MAGIC_SIZE ||
            prepared.compressedSize >= ZIP64_MAGIC_SIZE
        val aesExtra = if (useAes) buildAesExtra(WinZipAes.STRENGTH_256, actualMethod) else EMPTY_BYTES
        val timeExtra = buildExtendedTimestampExtra(mtimeSeconds)
        val zip64LocalExtra = if (needsZip64Sizes) {
            ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(EXTRA_ZIP64.toShort())
                putShort(16.toShort())
                putLong(prepared.uncompressedSize)
                putLong(prepared.compressedSize)
            }.array()
        } else {
            EMPTY_BYTES
        }
        val localExtra = zip64LocalExtra + aesExtra + timeExtra
        val localHeaderOffset = currentPosition
        val versionNeeded = if (needsZip64Sizes || localHeaderOffset >= ZIP64_MAGIC_SIZE || useAes) 51 else 20

        val lfh = ByteBuffer.allocate(30 + prepared.nameBytes.size + localExtra.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        lfh.putInt(SIG_LOCAL_FILE_HEADER)
        lfh.putShort(versionNeeded.toShort())
        lfh.putShort(gpbf.toShort())
        lfh.putShort(headerMethod.toShort())
        lfh.putShort(dosTime.toShort())
        lfh.putShort(dosDate.toShort())
        lfh.putInt(prepared.crc32.toInt())
        lfh.putInt(if (needsZip64Sizes) ZIP64_MAGIC_SIZE.toInt() else prepared.compressedSize.toInt())
        lfh.putInt(if (needsZip64Sizes) ZIP64_MAGIC_SIZE.toInt() else prepared.uncompressedSize.toInt())
        lfh.putShort(prepared.nameBytes.size.toShort())
        lfh.putShort(localExtra.size.toShort())
        lfh.put(prepared.nameBytes)
        lfh.put(localExtra)
        writeBytes(lfh.array(), 0, lfh.capacity())

        if (prepared.salt != null && prepared.verifier != null) {
            writeBytes(prepared.salt, 0, prepared.salt.size)
            writeBytes(prepared.verifier, 0, prepared.verifier.size)
        }
        if (prepared.payloadLen > 0) {
            writeBytes(prepared.payloadBytes, 0, prepared.payloadLen)
        }
        if (prepared.authCode != null) {
            writeBytes(prepared.authCode, 0, prepared.authCode.size)
        }

        entries += WrittenEntry(
            nameBytes = prepared.nameBytes,
            versionMadeBy = versionMadeBy,
            versionNeeded = versionNeeded,
            gpbf = gpbf,
            method = headerMethod,
            dosTime = dosTime,
            dosDate = dosDate,
            crc32 = prepared.crc32,
            compressedSize = prepared.compressedSize,
            uncompressedSize = prepared.uncompressedSize,
            externalAttributes = extAttr,
            localHeaderOffset = localHeaderOffset,
            aesExtra = aesExtra,
            timeExtra = timeExtra
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

                var lastProgressMillis = System.currentTimeMillis()
                var progressAcc = 0L

                if (actualMethod == ZipEntry.STORED) {
                    val ioBuf = ByteArray(IO_BUFFER_SIZE)
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
                } else if (uncompressedSizeHint > PARALLEL_BLOCK_SIZE || uncompressedSizeHint < 0L) {
                    val blockQueue = ArrayDeque<Future<CompressedBlock>>()
                    fun drainOneBlock() {
                        val future = blockQueue.removeFirst()
                        val block = try {
                            future.get()
                        } catch (e: InterruptedException) {
                            while (blockQueue.isNotEmpty()) blockQueue.removeFirst().cancel(true)
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException().apply { initCause(e) }
                        } catch (e: ExecutionException) {
                            while (blockQueue.isNotEmpty()) blockQueue.removeFirst().cancel(true)
                            val cause = e.cause
                            if (cause is IOException) throw cause
                            throw IOException(cause)
                        }
                        if (block.compressed.isNotEmpty()) {
                            payloadOut.write(block.compressed, 0, block.compressed.size)
                        }
                        if (!useAes) {
                            crcValue = crc32Combine(crcValue, block.crc32, block.uncompressedLen)
                        }
                        progressAcc += block.uncompressedLen
                        val now = System.currentTimeMillis()
                        if (listener != null && now >= lastProgressMillis + intervalMillis) {
                            listener(progressAcc)
                            lastProgressMillis = now
                            progressAcc = 0L
                        }
                    }

                    var prevChunk: ByteArray? = null
                    var prevLen = 0
                    try {
                        while (true) {
                            val currChunk = borrowBlockBuffer()
                            var currLen = 0
                            while (currLen < PARALLEL_BLOCK_SIZE) {
                                val n = inputStream.read(currChunk, currLen, PARALLEL_BLOCK_SIZE - currLen)
                                if (n == -1) break
                                currLen += n
                            }
                            if (Thread.interrupted()) {
                                releaseBlockBuffer(currChunk)
                                if (prevChunk != null) releaseBlockBuffer(prevChunk)
                                while (blockQueue.isNotEmpty()) blockQueue.removeFirst().cancel(true)
                                throw InterruptedIOException()
                            }
                            if (currLen == 0) {
                                releaseBlockBuffer(currChunk)
                                if (prevChunk != null) {
                                    uncompressedSize += prevLen
                                    blockQueue.addLast(submitDeflateBlock(prevChunk, prevLen, isLast = true))
                                    prevChunk = null
                                } else {
                                    val emptyChunk = borrowBlockBuffer()
                                    blockQueue.addLast(submitDeflateBlock(emptyChunk, 0, isLast = true))
                                }
                                break
                            }
                            if (prevChunk != null) {
                                uncompressedSize += prevLen
                                blockQueue.addLast(submitDeflateBlock(prevChunk, prevLen, isLast = false))
                                if (blockQueue.size >= workerCount) {
                                    drainOneBlock()
                                }
                            }
                            prevChunk = currChunk
                            prevLen = currLen
                        }
                        while (blockQueue.isNotEmpty()) {
                            drainOneBlock()
                        }
                    } finally {
                        if (prevChunk != null) {
                            releaseBlockBuffer(prevChunk)
                        }
                    }
                } else {
                    val ioBuf = ByteArray(IO_BUFFER_SIZE)
                    val deflateBuf = ByteArray(IO_BUFFER_SIZE)
                    val deflater = acquireDeflater()
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
                        releaseDeflater(deflater)
                    }
                    crcValue = if (useAes) 0L else crc.value
                }

                if (actualMethod == ZipEntry.STORED) {
                    crcValue = if (useAes) 0L else crc.value
                }
                if (progressAcc > 0) {
                    listener?.invoke(progressAcc)
                }
                if (encryptor != null) {
                    val authCode = encryptor.finishAuthCode()
                    writeBytes(authCode, 0, authCode.size)
                    compressedSize += authCode.size
                    crcValue = 0L
                }
            } finally {
                encryptor?.close()
            }
        } else {
            listener?.invoke(0L)
        }

        val needsZip64Sizes = uncompressedSize >= ZIP64_MAGIC_SIZE || compressedSize >= ZIP64_MAGIC_SIZE
        val lfhCrcField = crcValue.toInt()
        val lfhCompField = if (needsZip64Sizes || needsZip64Placeholder) ZIP64_MAGIC_SIZE.toInt() else compressedSize.toInt()
        val lfhUncompField = if (needsZip64Sizes || needsZip64Placeholder) ZIP64_MAGIC_SIZE.toInt() else uncompressedSize.toInt()

        val skipPatch = isDirectory ||
            (uncompressedSize == 0L && compressedSize == 0L && crcValue == 0L && !needsZip64Placeholder)
        if (!skipPatch) {
            if (localHeaderOffset >= bufferStartPosition) {
                val lfhInBuf = (localHeaderOffset - bufferStartPosition).toInt()
                val buf = ByteBuffer.wrap(writeBuffer).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(lfhInBuf + 14, lfhCrcField)
                buf.putInt(lfhInBuf + 18, lfhCompField)
                buf.putInt(lfhInBuf + 22, lfhUncompField)
                if (needsZip64Placeholder) {
                    val z64InBuf = lfhInBuf + 30 + nameBytes.size
                    buf.putShort(z64InBuf, EXTRA_ZIP64.toShort())
                    buf.putShort(z64InBuf + 2, 16.toShort())
                    buf.putLong(z64InBuf + 4, uncompressedSize)
                    buf.putLong(z64InBuf + 12, compressedSize)
                }
            } else {
                flushBuffer()
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
            }
        }

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

    private fun borrowBlockBuffer(): ByteArray =
        blockBufferPool.poll() ?: ByteArray(PARALLEL_BLOCK_SIZE)

    private fun releaseBlockBuffer(buffer: ByteArray) {
        if (blockBufferPool.size < workerCount + 4) {
            blockBufferPool.offer(buffer)
        }
    }

    private fun submitDeflateBlock(
        chunk: ByteArray,
        chunkLen: Int,
        isLast: Boolean
    ): Future<CompressedBlock> =
        executor.submit(Callable {
            try {
                val outCrc = LongArray(1)
                val nativeBytes = Syscall.nativeDeflateBlock(
                    chunk, chunkLen, compressionLevel.zipLevel, isLast, outCrc
                )
                if (nativeBytes != null) {
                    return@Callable CompressedBlock(
                        compressed = nativeBytes,
                        crc32 = outCrc[0] and 0xFFFFFFFFL,
                        uncompressedLen = chunkLen.toLong()
                    )
                }
                val crc = CRC32()
                if (chunkLen > 0) crc.update(chunk, 0, chunkLen)
                val deflater = acquireDeflater()
                try {
                    val out = ByteArrayOutputStream(minOf(chunkLen, 128 * 1024).coerceAtLeast(64))
                    val deflateBuf = ByteArray(IO_BUFFER_SIZE)
                    deflater.setInput(chunk, 0, chunkLen)
                    if (isLast) {
                        deflater.finish()
                        while (!deflater.finished()) {
                            val c = deflater.deflate(deflateBuf)
                            if (c > 0) out.write(deflateBuf, 0, c)
                        }
                    } else {
                        while (!deflater.needsInput()) {
                            val c = deflater.deflate(deflateBuf, 0, deflateBuf.size, Deflater.NO_FLUSH)
                            if (c > 0) out.write(deflateBuf, 0, c)
                        }
                        do {
                            val c = deflater.deflate(deflateBuf, 0, deflateBuf.size, Deflater.SYNC_FLUSH)
                            if (c > 0) out.write(deflateBuf, 0, c)
                        } while (c == deflateBuf.size)
                    }
                    CompressedBlock(
                        compressed = out.toByteArray(),
                        crc32 = crc.value,
                        uncompressedLen = chunkLen.toLong()
                    )
                } finally {
                    releaseDeflater(deflater)
                }
            } finally {
                releaseBlockBuffer(chunk)
            }
        })

    private fun acquireDeflater(): Deflater {
        val deflater = deflaterPool.poll()
        if (deflater != null) {
            deflater.reset()
            return deflater
        }
        return Deflater(compressionLevel.zipLevel, true)
    }

    private fun releaseDeflater(deflater: Deflater) {
        deflaterPool.offer(deflater)
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
        drainAllPendingEntries()
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
            sourceChannel.position(srcPos)
            while (remaining > 0) {
                val toRead = min(remaining, copyBuf.size.toLong()).toInt()
                val bb = ByteBuffer.wrap(copyBuf, 0, toRead)
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
            drainAllPendingEntries()
            writeCentralDirectoryAndEocd()
            flushBuffer()
        } finally {
            cancelPendingEntries()
            blockBufferPool.clear()
            try {
                executor.shutdownNow()
            } finally {
                while (true) {
                    val deflater = deflaterPool.poll() ?: break
                    deflater.end()
                }
                channel.close()
            }
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
        bufferStartPosition += writeBufferPos
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
        dosCalendar.timeInMillis = fileTime.toMillis()
        val year = (dosCalendar.get(GregorianCalendar.YEAR) - 1980).coerceIn(0, 127)
        val month = dosCalendar.get(GregorianCalendar.MONTH) + 1
        val day = dosCalendar.get(GregorianCalendar.DAY_OF_MONTH)
        val hour = dosCalendar.get(GregorianCalendar.HOUR_OF_DAY)
        val minute = dosCalendar.get(GregorianCalendar.MINUTE)
        val second = dosCalendar.get(GregorianCalendar.SECOND) ushr 1
        val dosTime = (hour shl 11) or (minute shl 5) or second
        val dosDate = (year shl 9) or (month shl 5) or day
        return dosTime to dosDate
    }

    private class CompletedFuture<T>(private val value: T) : Future<T> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): T = value
        override fun get(timeout: Long, unit: TimeUnit): T = value
    }

    private class PendingEntry(
        val future: Future<PreparedEntry>,
        val sizeHint: Long,
        val listener: ((Long) -> Unit)?
    )

    private class PreparedEntry(
        val nameBytes: ByteArray,
        val isDirectory: Boolean,
        val isSymbolicLink: Boolean,
        val lastModifiedTime: FileTime?,
        val mode: Set<PosixFileModeBit>,
        val uncompressedSize: Long,
        val compressedSize: Long,
        val crc32: Long,
        val salt: ByteArray?,
        val verifier: ByteArray?,
        val payloadBytes: ByteArray,
        val payloadLen: Int,
        val authCode: ByteArray?
    )

    private class CompressedBlock(
        val compressed: ByteArray,
        val crc32: Long,
        val uncompressedLen: Long
    )

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
        private const val IO_BUFFER_SIZE = 128 * 1024
        private const val WRITE_BUFFER_SIZE = 512 * 1024
        private const val PARALLEL_ENTRY_MAX_SIZE = 4 * 1024 * 1024L
        private const val MAX_PENDING_BYTES = 32 * 1024 * 1024L
        private const val PARALLEL_BLOCK_SIZE = 1024 * 1024
        private val EMPTY_BYTES = ByteArray(0)

        private fun gf2MatrixTimes(mat: LongArray, vec: Long): Long {
            var sum = 0L
            var v = vec
            var idx = 0
            while (v != 0L) {
                if ((v and 1L) != 0L) {
                    sum = sum xor mat[idx]
                }
                v = v ushr 1
                idx++
            }
            return sum
        }

        private fun gf2MatrixSquare(square: LongArray, mat: LongArray) {
            for (n in 0 until 32) {
                square[n] = gf2MatrixTimes(mat, mat[n])
            }
        }

        private fun crc32Combine(crc1Init: Long, crc2Init: Long, len2Init: Long): Long {
            var len2 = len2Init
            if (len2 <= 0L) return crc1Init
            var crc1 = crc1Init and 0xFFFFFFFFL
            val crc2 = crc2Init and 0xFFFFFFFFL
            val even = LongArray(32)
            val odd = LongArray(32)
            odd[0] = 0xedb88320L
            var row = 1L
            for (n in 1 until 32) {
                odd[n] = row
                row = row shl 1
            }
            gf2MatrixSquare(even, odd)
            gf2MatrixSquare(odd, even)
            while (len2 != 0L) {
                gf2MatrixSquare(even, odd)
                if ((len2 and 1L) != 0L) {
                    crc1 = gf2MatrixTimes(even, crc1)
                }
                len2 = len2 ushr 1
                if (len2 == 0L) break
                gf2MatrixSquare(odd, even)
                if ((len2 and 1L) != 0L) {
                    crc1 = gf2MatrixTimes(odd, crc1)
                }
                len2 = len2 ushr 1
            }
            return (crc1 xor crc2) and 0xFFFFFFFFL
        }
    }
}
