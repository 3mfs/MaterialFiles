/*
 * Copyright (c) 2026 Mox Apps
 * All Rights Reserved.
 */

package com.moxapps.files.provider.archive.zip

import java.io.IOException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WinZip AE-1 / AE-2 encryption and decryption helpers.
 * Uses PBKDF2-HMAC-SHA1 + little-endian 128-bit counter AES + 10-byte HMAC-SHA1.
 */
internal object WinZipAes {
    const val EXTRA_HEADER_ID = 0x9901
    const val METHOD_AES = 99
    const val VERSION_AE1 = 1
    const val VERSION_AE2 = 2
    const val STRENGTH_128 = 1
    const val STRENGTH_192 = 2
    const val STRENGTH_256 = 3
    const val AUTH_CODE_SIZE = 10
    const val PASSWORD_VERIFIER_SIZE = 2

    private const val PBKDF2_ITERATIONS = 1000

    fun saltSize(strength: Int): Int = when (strength) {
        STRENGTH_128 -> 8
        STRENGTH_192 -> 12
        STRENGTH_256 -> 16
        else -> throw IOException("Unsupported WinZip AES strength: $strength")
    }

    fun keySizeBytes(strength: Int): Int = when (strength) {
        STRENGTH_128 -> 16
        STRENGTH_192 -> 24
        STRENGTH_256 -> 32
        else -> throw IOException("Unsupported WinZip AES strength: $strength")
    }

    private fun deriveKeys(password: String, salt: ByteArray, strength: Int): DerivedKeys {
        val keyBytes = keySizeBytes(strength)
        val totalBytes = keyBytes * 2 + PASSWORD_VERIFIER_SIZE
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, totalBytes * 8)
        val derived = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val aesKey = derived.copyOfRange(0, keyBytes)
        val hmacKey = derived.copyOfRange(keyBytes, keyBytes * 2)
        val verifier = derived.copyOfRange(keyBytes * 2, totalBytes)
        Arrays.fill(derived, 0)
        return DerivedKeys(aesKey, hmacKey, verifier)
    }

    fun tryCreateDecryptor(
        password: String,
        salt: ByteArray,
        expectedVerifier: ByteArray,
        strength: Int
    ): Decryptor? {
        val keys = deriveKeys(password, salt, strength)
        if (!Arrays.equals(keys.verifier, expectedVerifier)) {
            keys.clear()
            return null
        }
        return Decryptor(keys)
    }

    fun createEncryptor(password: String, strength: Int = STRENGTH_256): Encryptor {
        val salt = ByteArray(saltSize(strength)).apply { SecureRandom().nextBytes(this) }
        val keys = deriveKeys(password, salt, strength)
        return Encryptor(salt, keys)
    }

    internal class DerivedKeys(
        val aesKey: ByteArray,
        val hmacKey: ByteArray,
        val verifier: ByteArray
    ) {
        fun clear() {
            Arrays.fill(aesKey, 0)
            Arrays.fill(hmacKey, 0)
            Arrays.fill(verifier, 0)
        }
    }

    private open class AesCounterCipher(aesKey: ByteArray) {
        private val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"))
        }
        private val counter = ByteArray(16)
        private val counterBatch = ByteArray(BATCH_SIZE)
        private val keystream = ByteArray(BATCH_SIZE)
        private var keystreamPos = BATCH_SIZE
        private var keystreamLimit = BATCH_SIZE

        protected fun applyKeystream(buffer: ByteArray, offset: Int, length: Int) {
            var i = offset
            val end = offset + length
            while (i < end) {
                if (keystreamPos >= keystreamLimit) {
                    val remainingBytes = end - i
                    val blocksNeeded = ((remainingBytes + 15) ushr 4).coerceIn(1, BATCH_BLOCKS)
                    val batchBytes = blocksNeeded shl 4
                    var bOff = 0
                    while (bOff < batchBytes) {
                        incrementLittleEndianCounter()
                        System.arraycopy(counter, 0, counterBatch, bOff, 16)
                        bOff += 16
                    }
                    cipher.update(counterBatch, 0, batchBytes, keystream, 0)
                    keystreamPos = 0
                    keystreamLimit = batchBytes
                }
                val chunk = minOf(end - i, keystreamLimit - keystreamPos)
                for (j in 0 until chunk) {
                    buffer[i + j] = (buffer[i + j].toInt() xor keystream[keystreamPos + j].toInt()).toByte()
                }
                i += chunk
                keystreamPos += chunk
            }
        }

        private fun incrementLittleEndianCounter() {
            for (idx in 0 until 16) {
                val next = (counter[idx].toInt() and 0xFF) + 1
                counter[idx] = next.toByte()
                if (next <= 0xFF) {
                    break
                }
            }
        }

        companion object {
            private const val BATCH_BLOCKS = 256
            private const val BATCH_SIZE = BATCH_BLOCKS * 16
        }
    }

    class Decryptor internal constructor(private val keys: DerivedKeys) {
        private val ctr = object : AesCounterCipher(keys.aesKey) {
            fun decrypt(buffer: ByteArray, offset: Int, length: Int) {
                applyKeystream(buffer, offset, length)
            }
        }
        private val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(keys.hmacKey, "HmacSHA1"))
        }

        fun decrypt(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            mac.update(buffer, offset, length)
            ctr.decrypt(buffer, offset, length)
        }

        fun verifyAuthCode(expectedAuthCode: ByteArray): Boolean {
            val fullMac = mac.doFinal()
            val actual = fullMac.copyOfRange(0, AUTH_CODE_SIZE)
            return Arrays.equals(actual, expectedAuthCode)
        }

        fun close() {
            keys.clear()
        }
    }

    class Encryptor internal constructor(
        val salt: ByteArray,
        private val keys: DerivedKeys
    ) {
        val verifier: ByteArray = keys.verifier.copyOf()

        private val ctr = object : AesCounterCipher(keys.aesKey) {
            fun encrypt(buffer: ByteArray, offset: Int, length: Int) {
                applyKeystream(buffer, offset, length)
            }
        }
        private val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(keys.hmacKey, "HmacSHA1"))
        }

        fun encrypt(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            ctr.encrypt(buffer, offset, length)
            mac.update(buffer, offset, length)
        }

        fun finishAuthCode(): ByteArray {
            val fullMac = mac.doFinal()
            return fullMac.copyOfRange(0, AUTH_CODE_SIZE)
        }

        fun close() {
            keys.clear()
        }
    }
}
