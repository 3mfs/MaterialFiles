/*
 * Copyright (c) 2026 MaterialFilesExtended Contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.zip.Deflater

enum class ArchiveCompressionLevel(
    val zipLevel: Int,
    val sevenZLevel: Int,
    val xzLevel: Int
) {
    STORE(Deflater.NO_COMPRESSION, 0, 0),
    FAST(Deflater.BEST_SPEED, 1, 1),
    NORMAL(3, 2, 2),
    MAXIMUM(6, 5, 6)
}

enum class SevenZCompressionMethod(val libArchiveValue: String) {
    LZMA2("lzma2"),
    LZMA("lzma1"),
    COPY("store")
}

@Parcelize
data class ArchiveOptions(
    val format: Int,
    val filter: Int,
    val compressionLevel: ArchiveCompressionLevel = ArchiveCompressionLevel.NORMAL,
    val sevenZMethod: SevenZCompressionMethod = SevenZCompressionMethod.LZMA2,
    val password: String? = null
) : Parcelable
