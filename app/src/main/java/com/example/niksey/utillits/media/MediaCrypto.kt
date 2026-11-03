package com.example.niksey.utillits.media

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Потоковое шифрование медиа (AES-256-GCM, по частям).
 *
 * Формат файла:
 *   MAGIC "NKM1" (4) | baseIV (12) | чанки...
 *   чанк: флаг (1: 0 — не последний, 1 — последний) | длина (4) | ciphertext+tag
 *
 * Защита:
 *  • у каждого файла свой случайный ключ (AES-256), поэтому повторов nonce между файлами нет;
 *  • nonce чанка = baseIV, в последние 4 байта которого записан номер чанка;
 *  • AAD чанка = внешний контекст (беседа + id сообщения) + номер чанка + флаг последнего чанка.
 *    Поэтому нельзя переставить чанки, обрезать файл или переложить его в другое сообщение.
 *
 * Чанки по 64 КБ — файлы не загружаются в память целиком.
 */
object MediaCrypto {

    private val MAGIC = byteArrayOf(0x4E, 0x4B, 0x4D, 0x31) // "NKM1"
    private const val IV_LEN = 12
    private const val TAG_LEN_BYTES = 16
    private const val CHUNK_SIZE = 64 * 1024
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FLAG_MORE = 0
    private const val FLAG_LAST = 1

    private val random = SecureRandom()

    fun newFileKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey, aad: ByteArray) {
        val baseIv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        output.write(MAGIC)
        output.write(baseIv)

        // Читаем с опережением на один чанк, чтобы знать, какой из них последний
        var current = readChunk(input) ?: ByteArray(0)
        var index = 0
        while (true) {
            val next = readChunk(input)
            val flag = if (next == null) FLAG_LAST else FLAG_MORE
            writeChunk(output, current, index, flag, baseIv, key, aad)
            if (next == null) break
            current = next
            index++
        }
        output.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey, aad: ByteArray) {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
        require(magic.contentEquals(MAGIC)) { "Неизвестный формат медиа" }
        val baseIv = ByteArray(IV_LEN).also { data.readFully(it) }

        var index = 0
        while (true) {
            val flag = data.read()
            if (flag == -1) throw EOFException("Файл обрезан")
            require(flag == FLAG_MORE || flag == FLAG_LAST) { "Некорректный чанк" }

            val length = data.readInt()
            require(length in TAG_LEN_BYTES..(CHUNK_SIZE + TAG_LEN_BYTES)) { "Некорректная длина чанка" }
            val body = ByteArray(length).also { data.readFully(it) }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN_BYTES * 8, nonceFor(baseIv, index)))
            cipher.updateAAD(chunkAad(aad, index, flag))
            output.write(cipher.doFinal(body))

            if (flag == FLAG_LAST) {
                require(data.read() == -1) { "Лишние данные после последнего чанка" }
                break
            }
            index++
        }
        output.flush()
    }

    private fun writeChunk(
        output: OutputStream,
        plain: ByteArray,
        index: Int,
        flag: Int,
        baseIv: ByteArray,
        key: SecretKey,
        aad: ByteArray
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LEN_BYTES * 8, nonceFor(baseIv, index)))
        cipher.updateAAD(chunkAad(aad, index, flag))
        val body = cipher.doFinal(plain)

        output.write(flag)
        output.write(ByteBuffer.allocate(4).putInt(body.size).array())
        output.write(body)
    }

    /** Читает до CHUNK_SIZE байт; null — если данных больше нет. */
    private fun readChunk(input: InputStream): ByteArray? {
        val buffer = ByteArray(CHUNK_SIZE)
        var filled = 0
        while (filled < CHUNK_SIZE) {
            val n = input.read(buffer, filled, CHUNK_SIZE - filled)
            if (n == -1) break
            filled += n
        }
        return if (filled == 0) null else buffer.copyOf(filled)
    }

    private fun nonceFor(baseIv: ByteArray, index: Int): ByteArray {
        val nonce = baseIv.copyOf()
        val counter = ByteBuffer.allocate(4).putInt(index).array()
        for (i in 0 until 4) nonce[IV_LEN - 4 + i] = (nonce[IV_LEN - 4 + i].toInt() xor counter[i].toInt()).toByte()
        return nonce
    }

    private fun chunkAad(aad: ByteArray, index: Int, flag: Int): ByteArray =
        ByteBuffer.allocate(aad.size + 5).put(aad).putInt(index).put(flag.toByte()).array()
}
