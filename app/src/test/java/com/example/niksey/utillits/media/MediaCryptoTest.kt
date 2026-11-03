package com.example.niksey.utillits.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class MediaCryptoTest {

    private val aad = "pm:a|b|msg1".toByteArray()

    private fun roundTrip(plain: ByteArray): ByteArray {
        val key = MediaCrypto.newFileKey()
        val enc = ByteArrayOutputStream()
        MediaCrypto.encrypt(ByteArrayInputStream(plain), enc, key, aad)
        val dec = ByteArrayOutputStream()
        MediaCrypto.decrypt(ByteArrayInputStream(enc.toByteArray()), dec, key, aad)
        return dec.toByteArray()
    }

    @Test fun roundTripsAtChunkBoundaries() {
        for (size in listOf(0, 1, 100, 65535, 65536, 65537, 131072, 200_000)) {
            val plain = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
            assertArrayEquals("size $size", plain, roundTrip(plain))
        }
    }

    @Test fun ciphertextDoesNotContainPlaintext() {
        val plain = "secret photo bytes ".repeat(1000).toByteArray()
        val enc = ByteArrayOutputStream()
        MediaCrypto.encrypt(ByteArrayInputStream(plain), enc, MediaCrypto.newFileKey(), aad)
        val text = String(enc.toByteArray(), Charsets.ISO_8859_1)
        assert(!text.contains("secret photo")) { "plaintext visible in ciphertext" }
    }

    @Test fun wrongAadIsRejected() {
        val key = MediaCrypto.newFileKey()
        val enc = ByteArrayOutputStream()
        MediaCrypto.encrypt(ByteArrayInputStream(ByteArray(70_000)), enc, key, aad)
        assertThrows(Exception::class.java) {
            MediaCrypto.decrypt(ByteArrayInputStream(enc.toByteArray()), ByteArrayOutputStream(), key, "other".toByteArray())
        }
    }

    @Test fun tamperedByteIsRejected() {
        val key = MediaCrypto.newFileKey()
        val enc = ByteArrayOutputStream()
        MediaCrypto.encrypt(ByteArrayInputStream(ByteArray(70_000)), enc, key, aad)
        val bytes = enc.toByteArray()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            MediaCrypto.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, aad)
        }
    }

    @Test fun truncatedFileIsRejected() {
        val key = MediaCrypto.newFileKey()
        val enc = ByteArrayOutputStream()
        MediaCrypto.encrypt(ByteArrayInputStream(ByteArray(200_000)), enc, key, aad)
        val bytes = enc.toByteArray()
        assertThrows(Exception::class.java) {
            MediaCrypto.decrypt(ByteArrayInputStream(bytes.copyOf(bytes.size - 1000)), ByteArrayOutputStream(), key, aad)
        }
    }
}
