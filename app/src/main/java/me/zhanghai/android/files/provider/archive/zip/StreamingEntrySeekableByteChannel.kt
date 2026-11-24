/*
 * Copyright (c) 2026 MaterialFilesExtended Contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.zip

import java8.nio.channels.SeekableByteChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import kotlin.math.min

/**
 * Zero-copy read-only SeekableByteChannel backed by a contiguous slice of an underlying channel.
 */
internal class SliceSeekableByteChannel(
    private val underlying: SeekableByteChannel,
    private val baseOffset: Long,
    private val sliceSize: Long
) : SeekableByteChannel {
    private val lock = Any()
    private var position = 0L
    private var open = true

    override fun isOpen(): Boolean = synchronized(lock) { open }

    @Throws(IOException::class)
    override fun close() {
        synchronized(lock) {
            if (!open) return
            open = false
            underlying.close()
        }
    }

    @Throws(IOException::class)
    override fun position(): Long = synchronized(lock) {
        ensureOpen()
        position
    }

    @Throws(IOException::class)
    override fun position(newPosition: Long): SeekableByteChannel = synchronized(lock) {
        ensureOpen()
        require(newPosition >= 0) { "Negative position: $newPosition" }
        position = newPosition
        this
    }

    @Throws(IOException::class)
    override fun size(): Long = synchronized(lock) {
        ensureOpen()
        sliceSize
    }

    @Throws(IOException::class)
    override fun read(dst: ByteBuffer): Int = synchronized(lock) {
        ensureOpen()
        val remainingInSlice = sliceSize - position
        if (remainingInSlice <= 0) {
            return -1
        }
        val toRead = min(dst.remaining().toLong(), remainingInSlice).toInt()
        if (toRead == 0) {
            return 0
        }
        val oldLimit = dst.limit()
        dst.limit(dst.position() + toRead)
        val bytesRead = try {
            underlying.position(baseOffset + position)
            underlying.read(dst)
        } finally {
            dst.limit(oldLimit)
        }
        if (bytesRead > 0) {
            position += bytesRead
        }
        bytesRead
    }

    override fun write(src: ByteBuffer): Int {
        throw NonWritableChannelException()
    }

    override fun truncate(size: Long): SeekableByteChannel {
        throw NonWritableChannelException()
    }

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }
}

/**
 * Streaming read-only SeekableByteChannel for compressed/encrypted archive entries.
 * - Normal sequential streaming and small header rewinds (<= 64 KiB) never touch disk.
 * - Backward seeks on entries <= 8 MiB materialize in memory once for instant random access.
 * - Backward seeks on entries > 8 MiB lazily spill to an unlinked temp file only when needed.
 */
internal class StreamingEntrySeekableByteChannel(
    private val entrySize: Long,
    initialStream: InputStream? = null,
    private val streamOpener: () -> InputStream
) : SeekableByteChannel {
    private val lock = Any()
    private var open = true
    private var position = 0L

    private var stream: InputStream? = initialStream
    private var streamPos = 0L

    private val headCache = ByteArray(HEAD_CACHE_SIZE)
    private var headCacheLength = 0

    private var memoryData: ByteArray? = null
    private var spillFile: File? = null
    private var spillRaf: RandomAccessFile? = null

    override fun isOpen(): Boolean = synchronized(lock) { open }

    @Throws(IOException::class)
    override fun close() {
        synchronized(lock) {
            if (!open) return
            open = false
            try {
                stream?.close()
            } finally {
                stream = null
                memoryData = null
                try {
                    spillRaf?.close()
                } finally {
                    spillRaf = null
                    spillFile?.delete()
                    spillFile = null
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun position(): Long = synchronized(lock) {
        ensureOpen()
        position
    }

    @Throws(IOException::class)
    override fun position(newPosition: Long): SeekableByteChannel = synchronized(lock) {
        ensureOpen()
        require(newPosition >= 0) { "Negative position: $newPosition" }
        position = newPosition
        this
    }

    @Throws(IOException::class)
    override fun size(): Long = synchronized(lock) {
        ensureOpen()
        entrySize
    }

    @Throws(IOException::class)
    override fun read(dst: ByteBuffer): Int = synchronized(lock) {
        ensureOpen()
        if (entrySize >= 0 && position >= entrySize) {
            return -1
        }
        val requested = dst.remaining()
        if (requested == 0) {
            return 0
        }

        // Fast path 1: already materialized in memory.
        memoryData?.let { data ->
            if (position >= data.size) return -1
            val n = min(requested, data.size - position.toInt())
            dst.put(data, position.toInt(), n)
            position += n
            return n
        }

        // Fast path 2: already spilled to temp file after random backward seek on >8 MiB entry.
        spillRaf?.let { raf ->
            raf.seek(position)
            val tmp = ByteArray(min(requested, IO_BUFFER_SIZE))
            val n = raf.read(tmp)
            if (n <= 0) return -1
            dst.put(tmp, 0, n)
            position += n
            return n
        }

        // Fast path 3: reading from head cache (handles magic-byte/EXIF sniffing rewinds at start).
        if (position < headCacheLength) {
            val avail = min(requested, (headCacheLength - position).toInt())
            dst.put(headCache, position.toInt(), avail)
            position += avail
            return avail
        }

        // Forward or sequential read on live InputStream.
        if (position < streamPos) {
            // Caller sought backward past headCacheLength!
            materializeForRandomAccess()
            return read(dst)
        }

        val activeStream = ensureStream()
        if (position > streamPos) {
            val toSkip = position - streamPos
            // If jumping far forward in a huge file, still just skip through stream (or materialize if small).
            skipFully(activeStream, toSkip)
            streamPos = position
        }

        val maxRead = if (entrySize >= 0) {
            min(requested.toLong(), entrySize - position).toInt()
        } else {
            requested
        }
        val temp = ByteArray(min(maxRead, IO_BUFFER_SIZE))
        val readCount = activeStream.read(temp, 0, temp.size)
        if (readCount <= 0) {
            return -1
        }
        // Populate head cache if still within first HEAD_CACHE_SIZE bytes.
        if (streamPos < HEAD_CACHE_SIZE) {
            val cacheCopy = min(readCount, HEAD_CACHE_SIZE - streamPos.toInt())
            System.arraycopy(temp, 0, headCache, streamPos.toInt(), cacheCopy)
            val newEnd = streamPos.toInt() + cacheCopy
            if (newEnd > headCacheLength) {
                headCacheLength = newEnd
            }
        }
        streamPos += readCount
        position += readCount
        dst.put(temp, 0, readCount)
        return readCount
    }

    override fun write(src: ByteBuffer): Int {
        throw NonWritableChannelException()
    }

    override fun truncate(size: Long): SeekableByteChannel {
        throw NonWritableChannelException()
    }

    @Throws(IOException::class)
    private fun ensureStream(): InputStream {
        stream?.let { return it }
        val opened = streamOpener()
        stream = opened
        streamPos = 0L
        return opened
    }

    @Throws(IOException::class)
    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discard = ByteArray(IO_BUFFER_SIZE)
        while (remaining > 0) {
            val n = input.read(discard, 0, min(remaining, discard.size.toLong()).toInt())
            if (n <= 0) {
                break
            }
            if (streamPos < HEAD_CACHE_SIZE) {
                val cacheCopy = min(n, HEAD_CACHE_SIZE - streamPos.toInt())
                System.arraycopy(discard, 0, headCache, streamPos.toInt(), cacheCopy)
                val newEnd = streamPos.toInt() + cacheCopy
                if (newEnd > headCacheLength) {
                    headCacheLength = newEnd
                }
            }
            streamPos += n
            remaining -= n
        }
    }

    @Throws(IOException::class)
    private fun materializeForRandomAccess() {
        stream?.close()
        stream = null
        streamPos = 0L
        if (entrySize in 0..MAX_IN_MEMORY_RANDOM_ACCESS_SIZE) {
            val out = ByteArrayOutputStream(entrySize.toInt().coerceAtLeast(1024))
            streamOpener().use { input ->
                val buf = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            memoryData = out.toByteArray()
        } else {
            val tmpFile = File.createTempFile("mf_stream_", ".tmp")
            val raf = RandomAccessFile(tmpFile, "rw")
            // Unlink immediately on Linux so OS cleans up even if process dies.
            tmpFile.delete()
            var successful = false
            try {
                streamOpener().use { input ->
                    val buf = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                    }
                }
                successful = true
            } finally {
                if (!successful) {
                    raf.close()
                }
            }
            spillFile = tmpFile
            spillRaf = raf
        }
    }

    private fun ensureOpen() {
        if (!open) throw ClosedChannelException()
    }

    companion object {
        private const val HEAD_CACHE_SIZE = 64 * 1024
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val MAX_IN_MEMORY_RANDOM_ACCESS_SIZE = 8 * 1024 * 1024L
    }
}
