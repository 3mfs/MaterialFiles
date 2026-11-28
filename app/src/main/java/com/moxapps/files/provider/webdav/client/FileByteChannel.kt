package com.moxapps.files.provider.webdav.client

import android.util.Log
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import com.moxapps.files.provider.common.AbstractFileByteChannel
import com.moxapps.files.provider.common.EMPTY
import com.moxapps.files.provider.common.readFully
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.min

// https://blog.sphere.chronosempire.org.uk/2012/11/21/webdav-and-the-http-patch-nightmare
class FileByteChannel(
    private val resource: DavResource,
    isAppend: Boolean
) : AbstractFileByteChannel(isAppend) {
    private val patchSupport: PatchSupport by lazy { resource.getPatchSupport() }
    private var nextSequentialWritePosition = 0L
    private var sequentialWriteOutputStream: OutputStream? = null

    private var readCache: ByteArray? = null
    private var readCacheStart = 0L
    private var readCacheLength = 0
    private var cachedSize: Long? = null

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        if (size <= 0) {
            return ByteBuffer::class.EMPTY
        }
        val knownSize = cachedSize
        if (knownSize != null && position >= knownSize) {
            return ByteBuffer::class.EMPTY
        }

        val cache = readCache
        if (cache != null && position >= readCacheStart && position < readCacheStart + readCacheLength) {
            val offsetInCache = (position - readCacheStart).toInt()
            val available = readCacheLength - offsetInCache
            val toCopy = min(size, available)
            return ByteBuffer.wrap(cache, offsetInCache, toCopy).slice()
        }

        val desiredFetch = maxOf(size, READ_AHEAD_SIZE)
        val fetchSize = if (knownSize != null) {
            min(desiredFetch.toLong(), knownSize - position).toInt().coerceAtLeast(1)
        } else {
            desiredFetch
        }

        val inputStream = try {
            resource.getRangeCompat("*/*", position, fetchSize, null)
        } catch (e: HttpException) {
            if (e.code == HTTP_RANGE_NOT_SATISFIABLE) {
                // We were reading at/past end of file
                return ByteBuffer::class.EMPTY
            }
            throw e
        }

        val buffer = if (cache != null && cache.size >= fetchSize) cache else ByteArray(fetchSize)
        val limit = inputStream.use {
            it.readFully(buffer, 0, fetchSize)
        }
        if (limit <= 0) {
            return ByteBuffer::class.EMPTY
        }
        readCache = buffer
        readCacheStart = position
        readCacheLength = limit

        val toCopy = min(size, limit)
        return ByteBuffer.wrap(buffer, 0, toCopy).slice()
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        readCache = null
        readCacheLength = 0
        cachedSize = null
        when (patchSupport) {
            PatchSupport.APACHE ->
                resource.putRangeCompat(source, position) {}
            PatchSupport.SABRE ->
                resource.patchCompat(source, position) {}
            PatchSupport.NONE -> {
                if (position != nextSequentialWritePosition) {
                    throw IOException("Unsupported non-sequential write")
                }
                val outputStream = sequentialWriteOutputStream
                    ?: resource.putCompat().also { sequentialWriteOutputStream = it }
                val remaining = source.remaining()
                // I don't think we are using native or read-only ByteBuffer, so just call array()
                // here.
                outputStream.write(
                    source.array(), source.arrayOffset() + source.position(), remaining
                )
                nextSequentialWritePosition += remaining
            }
        }
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        readCache = null
        readCacheLength = 0
        cachedSize = null
        if (size == 0L) {
            resource.put(byteArrayOf().toRequestBody()) {}
        } else {
            throw IOException("Unsupported truncate to non-zero size")
        }
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        cachedSize?.let { return it }
        val propSize = try {
            Client.findProperties(resource, GetContentLength.NAME)[GetContentLength::class.java]
                ?.contentLength
        } catch (e: Exception) {
            Log.w("WebDavDebug", "PROPFIND GetContentLength failed for ${resource.location}", e)
            null
        }
        val resolvedSize = propSize ?: resource.headContentLengthCompat()
            ?: throw IOException("Missing Content-Length")
        Log.i("WebDavDebug", "onSize(${resource.location}) -> $resolvedSize (propSize=$propSize)")
        cachedSize = resolvedSize
        return resolvedSize
    }

    @Throws(IOException::class)
    override fun onClose() {
        readCache = null
        readCacheLength = 0
        sequentialWriteOutputStream?.close()
    }

    companion object {
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val READ_AHEAD_SIZE = 512 * 1024
    }
}
