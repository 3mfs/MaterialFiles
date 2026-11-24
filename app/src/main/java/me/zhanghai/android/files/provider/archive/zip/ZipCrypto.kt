/*
 * Copyright (c) 2026 MaterialFilesExtended Contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.zip

import java.nio.charset.Charset

/**
 * Standard PKWARE ZipCrypto (3-key stream cipher) decryptor.
 */
internal class ZipCryptoDecryptor private constructor(
    private var key0: Int,
    private var key1: Int,
    private var key2: Int
) {
    fun decrypt(buffer: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) {
            val plain = ((buffer[i].toInt() and 0xFF) xor decryptByte()) and 0xFF
            buffer[i] = plain.toByte()
            updateKeys(plain)
        }
    }

    private fun decryptByte(): Int {
        val temp = (key2 or 2) and 0xFFFF
        return ((temp * (temp xor 1)) ushr 8) and 0xFF
    }

    private fun updateKeys(charByte: Int) {
        key0 = crc32Update(key0, charByte)
        key1 = key1 + (key0 and 0xFF)
        key1 = key1 * 134775813 + 1
        key2 = crc32Update(key2, key1 ushr 24)
    }

    companion object {
        const val HEADER_SIZE = 12

        private val CRC32_TABLE = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else (c ushr 1)
            }
            c
        }

        private fun crc32Update(crc: Int, b: Int): Int =
            (crc ushr 8) xor CRC32_TABLE[(crc xor b) and 0xFF]

        fun tryCreate(
            password: String,
            charset: Charset,
            encryptedHeader: ByteArray,
            crc32: Long,
            dosTime: Int,
            hasDataDescriptor: Boolean
        ): ZipCryptoDecryptor? {
            if (encryptedHeader.size != HEADER_SIZE) return null
            val decryptor = ZipCryptoDecryptor(0x12345678, 0x23456789, 0x34567890)
            for (b in password.toByteArray(charset)) {
                decryptor.updateKeys(b.toInt() and 0xFF)
            }
            val headerCopy = encryptedHeader.copyOf()
            decryptor.decrypt(headerCopy, 0, HEADER_SIZE)
            val expectedByte = if (hasDataDescriptor) {
                (dosTime ushr 8) and 0xFF
            } else {
                ((crc32 ushr 24) and 0xFFL).toInt()
            }
            val actualByte = headerCopy[HEADER_SIZE - 1].toInt() and 0xFF
            return if (actualByte == expectedByte) decryptor else null
        }
    }
}
