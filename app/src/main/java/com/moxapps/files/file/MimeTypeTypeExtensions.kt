package com.moxapps.files.file

val MimeType.isApk: Boolean
    get() = this == MimeType.APK

val MimeType.isSupportedArchive: Boolean
    get() = this in supportedArchiveMimeTypes

private val supportedArchiveMimeTypes = mutableListOf(
    "application/gzip",
    "application/java-archive",
    "application/rar",
    "application/zip",
    "application/zstd",
    "application/vnd.android.package-archive",
    "application/vnd.debian.binary-package",
    "application/vnd.ms-cab-compressed",
    "application/vnd.rar",
    "application/x-7z-compressed",
    "application/x-bzip2",
    "application/x-cab",
    "application/x-compress",
    "application/x-cpio",
    "application/x-deb",
    "application/x-debian-package",
    "application/x-gtar",
    "application/x-gtar-compressed",
    "application/x-iso9660-image",
    "application/x-java-archive",
    "application/x-lha",
    "application/x-lzma",
    "application/x-redhat-package-manager",
    "application/x-tar",
    "application/x-ustar",
    "application/x-xz"
).map { it.asMimeType() }.toSet()

val MimeType.isImage: Boolean
    get() = icon == MimeTypeIcon.IMAGE

val MimeType.isAudio: Boolean
    get() = icon == MimeTypeIcon.AUDIO

val MimeType.isVideo: Boolean
    get() = icon == MimeTypeIcon.VIDEO

val MimeType.isMedia: Boolean
    get() = isAudio || isVideo

val MimeType.isPdf: Boolean
    get() = this == MimeType.PDF

private val stlMimeTypes = listOf(
    "application/vnd.ms-pki.stl",
    "model/stl",
    "application/sla",
    "application/x-stl"
).map { it.asMimeType() }.toSet()

private val threeMfMimeTypes = listOf(
    "application/vnd.ms-3mfdocument",
    "model/3mf"
).map { it.asMimeType() }.toSet()

val MimeType.isStl: Boolean
    get() = this in stlMimeTypes

val MimeType.is3mf: Boolean
    get() = this in threeMfMimeTypes

val MimeType.is3dModel: Boolean
    get() = isStl || is3mf
