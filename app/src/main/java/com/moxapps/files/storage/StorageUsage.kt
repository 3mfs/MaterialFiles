package com.moxapps.files.storage

import android.content.Context
import android.graphics.Color
import android.os.Environment
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.annotation.ColorInt
import com.moxapps.files.R
import com.moxapps.files.compat.getDescriptionCompat
import com.moxapps.files.compat.isPrimaryCompat
import com.moxapps.files.compat.pathCompat
import com.moxapps.files.file.JavaFile
import com.moxapps.files.util.getColorByAttr
import com.moxapps.files.util.isMounted
import com.moxapps.files.util.supportsExternalStorageManager
import com.moxapps.files.util.valueCompat
import java.io.File

data class StorageUsageInfo(
    val usedBytes: Long,
    val totalBytes: Long,
    val freeBytes: Long
) {
    val usedFraction: Float
        get() = if (totalBytes > 0L) {
            (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val usedPercent: Int
        get() = (usedFraction * 100f).toInt().coerceIn(0, 100)
}

data class StorageSegment(
    val id: Long,
    val label: String,
    val usedBytes: Long,
    val totalBytes: Long,
    @ColorInt val color: Int
)

data class StorageOverview(
    val totalUsedBytes: Long,
    val totalCapacityBytes: Long,
    val localDiskCount: Int,
    val remoteStorageCount: Int,
    val cacheBytes: Long,
    val segments: List<StorageSegment>
) {
    val usedFraction: Float
        get() = if (totalCapacityBytes > 0L) {
            (totalUsedBytes.toFloat() / totalCapacityBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val usedPercent: Int
        get() = (usedFraction * 100f).toInt().coerceIn(0, 100)
}

object StorageUsage {
    @ColorInt val COLOR_INTERNAL = Color.parseColor("#4F7CAC")
    @ColorInt val COLOR_ROOT = Color.parseColor("#7E78B8")
    @ColorInt val COLOR_SD_USB = Color.parseColor("#3B9E7E")
    @ColorInt val COLOR_DOCUMENT_TREE = Color.parseColor("#5B8DEF")
    @ColorInt val COLOR_WEBDAV = Color.parseColor("#4A90E2")
    @ColorInt val COLOR_SMB = Color.parseColor("#7C6CD9")
    @ColorInt val COLOR_SFTP = Color.parseColor("#2E9E76")
    @ColorInt val COLOR_FTP = Color.parseColor("#D98A2B")
    @ColorInt val COLOR_OTHER = Color.parseColor("#8A94A6")

    @ColorInt
    fun getBrandColor(storage: Storage, context: Context? = null): Int =
        when (storage) {
            is PrimaryStorageVolume ->
                context?.getColorByAttr(androidx.appcompat.R.attr.colorPrimary) ?: COLOR_INTERNAL
            is FileSystemRoot -> COLOR_ROOT
            is DocumentTree -> COLOR_DOCUMENT_TREE
            is WebDavServer -> COLOR_WEBDAV
            is SmbServer -> COLOR_SMB
            is SftpServer -> COLOR_SFTP
            is FtpServer -> COLOR_FTP
            is ExternalStorageShortcut -> COLOR_SD_USB
            else -> COLOR_OTHER
        }

    fun getStatusLabel(storage: Storage, context: Context): String =
        when (storage) {
            is DeviceStorage -> context.getString(
                if (storage.isVisible) R.string.storage_status_mounted else R.string.storage_status_hidden
            )
            is DocumentTree -> context.getString(R.string.storage_status_connected)
            is WebDavServer -> "WebDAV"
            is SmbServer -> "SMB"
            is SftpServer -> "SFTP"
            is FtpServer -> "FTP"
            is ExternalStorageShortcut -> context.getString(R.string.storage_status_mounted)
            else -> context.getString(R.string.storage_status_connected)
        }

    fun isRemoteStorage(storage: Storage): Boolean =
        storage is WebDavServer || storage is SmbServer || storage is SftpServer ||
            storage is FtpServer || (storage is DocumentTree && storage.linuxPath == null)

    fun getLinuxUsage(linuxPath: String): StorageUsageInfo? {
        var totalSpace = JavaFile.getTotalSpace(linuxPath)
        val freeSpace: Long
        when {
            totalSpace > 0L -> freeSpace = JavaFile.getFreeSpace(linuxPath)
            linuxPath == FileSystemRoot.LINUX_PATH -> {
                val systemPath = Environment.getRootDirectory().path
                totalSpace = JavaFile.getTotalSpace(systemPath)
                freeSpace = JavaFile.getFreeSpace(systemPath)
            }
            else -> freeSpace = 0L
        }
        if (totalSpace <= 0L) {
            return null
        }
        val usedSpace = (totalSpace - freeSpace).coerceAtLeast(0L)
        return StorageUsageInfo(usedSpace, totalSpace, freeSpace)
    }

    fun getStorageUsage(storage: Storage, context: Context): StorageUsageInfo? {
        storage.linuxPath?.let { return getLinuxUsage(it) }
        if (storage is DocumentTree) {
            return queryDocumentTreeUsage(storage, context)
        }
        return null
    }

    private fun queryDocumentTreeUsage(tree: DocumentTree, context: Context): StorageUsageInfo? {
        val uri = tree.uri.value
        val authority = uri.authority ?: return null
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val rootsUri = DocumentsContract.buildRootsUri(authority)
        return try {
            context.contentResolver.query(
                rootsUri,
                arrayOf(
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                val capCol = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_CAPACITY_BYTES)
                val availCol = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                while (cursor.moveToNext()) {
                    val rootDocId = if (idCol >= 0) cursor.getString(idCol) else null
                    if (rootDocId == null || rootDocId == treeDocId || cursor.count == 1) {
                        val cap = if (capCol >= 0 && !cursor.isNull(capCol)) cursor.getLong(capCol) else -1L
                        val avail = if (availCol >= 0 && !cursor.isNull(availCol)) cursor.getLong(availCol) else -1L
                        if (cap > 0L && avail >= 0L) {
                            return StorageUsageInfo((cap - avail).coerceAtLeast(0L), cap, avail)
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun computeOverview(
        storages: List<Storage>,
        volumes: List<StorageVolume>,
        context: Context
    ): StorageOverview {
        val segments = mutableListOf<StorageSegment>()
        val seenMountPaths = mutableSetOf<String>()
        var totalUsed = 0L
        var totalCapacity = 0L
        var localCount = 0
        var remoteCount = 0

        for (storage in storages) {
            if (isRemoteStorage(storage)) {
                remoteCount++
            } else {
                localCount++
            }
            val linuxPath = storage.linuxPath
            if (linuxPath != null) {
                if (!storage.isVisible && storage is FileSystemRoot) {
                    continue
                }
                if (!seenMountPaths.add(linuxPath)) {
                    continue
                }
            }
            val usage = getStorageUsage(storage, context) ?: continue
            // Avoid counting both PrimaryStorageVolume and FileSystemRoot if they share the same
            // underlying capacity or if FileSystemRoot is hidden.
            segments += StorageSegment(
                id = storage.id,
                label = storage.getName(context),
                usedBytes = usage.usedBytes,
                totalBytes = usage.totalBytes,
                color = getBrandColor(storage, context)
            )
            totalUsed += usage.usedBytes
            totalCapacity += usage.totalBytes
        }

        if (Environment::class.supportsExternalStorageManager()) {
            for (volume in volumes) {
                if (!volume.isPrimaryCompat && volume.isMounted) {
                    val volPath = volume.pathCompat
                    if (seenMountPaths.add(volPath)) {
                        localCount++
                        val usage = getLinuxUsage(volPath) ?: continue
                        segments += StorageSegment(
                            id = volume.hashCode().toLong(),
                            label = volume.getDescriptionCompat(context),
                            usedBytes = usage.usedBytes,
                            totalBytes = usage.totalBytes,
                            color = COLOR_SD_USB
                        )
                        totalUsed += usage.usedBytes
                        totalCapacity += usage.totalBytes
                    }
                }
            }
        }

        val cacheBytes = computeAppCacheSize(context)
        return StorageOverview(
            totalUsedBytes = totalUsed,
            totalCapacityBytes = totalCapacity,
            localDiskCount = localCount,
            remoteStorageCount = remoteCount,
            cacheBytes = cacheBytes,
            segments = segments
        )
    }

    fun computeAppCacheSize(context: Context): Long {
        var total = dirSize(context.cacheDir)
        context.externalCacheDir?.let { total += dirSize(it) }
        return total
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        val children = dir.listFiles() ?: return 0L
        var sum = 0L
        for (child in children) {
            sum += if (child.isDirectory) dirSize(child) else child.length()
        }
        return sum
    }
}
