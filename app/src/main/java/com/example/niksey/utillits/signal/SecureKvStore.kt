package com.example.niksey.utillits.signal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Зашифрованное key-value хранилище: ключи Signal, сессии и кэш расшифрованных сообщений.
 *
 * • Каждая запись — отдельный файл. Имя файла = SHA-256(ключ), поэтому id собеседников на диске не видны.
 * • Содержимое: AES-256-GCM, мастер-ключ лежит в Android Keystore и не покидает его.
 * • AAD = пространство имён + имя файла. Файл нельзя переименовать или подменить: расшифровка не пройдёт.
 * • Формат файла: IV(12) | AES-GCM( UTF(ключ) | длина | значение ).
 */
class SecureKvStore(context: Context, private val namespace: String) {

    private val dir = File(context.filesDir, "secure/$namespace").apply { mkdirs() }
    private val random = SecureRandom()

    fun put(key: String, value: ByteArray) {
        val payload = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).apply {
                writeUTF(key)
                writeInt(value.size)
                write(value)
                flush()
            }
        }.toByteArray()

        val fileName = fileNameFor(key)
        // Android Keystore не принимает свой IV для AES-GCM: IV генерирует сам Keystore
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        check(iv.size == IV_LEN) { "Неожиданная длина IV: ${iv.size}" }
        cipher.updateAAD(aad(fileName))
        val encrypted = cipher.doFinal(payload)

        // Пишем во временный файл и переименовываем: обрыв записи не портит предыдущую версию
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeBytes(iv + encrypted)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Не удалось сохранить запись" }
        }
    }

    fun get(key: String): ByteArray? {
        val file = File(dir, fileNameFor(key))
        if (!file.isFile) return null
        return try {
            readRecord(file)?.second
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    fun remove(key: String) {
        File(dir, fileNameFor(key)).delete()
    }

    /** Все записи пространства имён (нужно для загрузки сессий). */
    fun entries(): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .forEach { file ->
                try {
                    readRecord(file)?.let { (key, value) -> result[key] = value }
                } catch (_: Exception) {
                    file.delete()
                }
            }
        return result
    }

    fun clear() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    // ==================== внутреннее ====================

    private fun fileNameFor(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun aad(fileName: String): ByteArray = "$namespace|$fileName".toByteArray(Charsets.UTF_8)

    /** Возвращает (ключ, значение). Ключ восстанавливается из зашифрованного payload. */
    private fun readRecord(file: File): Pair<String, ByteArray>? {
        val bytes = file.readBytes()
        require(bytes.size > IV_LEN) { "Файл повреждён" }
        val iv = bytes.copyOfRange(0, IV_LEN)
        val body = bytes.copyOfRange(IV_LEN, bytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(file.name))
        val payload = cipher.doFinal(body)

        DataInputStream(payload.inputStream()).use { input ->
            val key = input.readUTF()
            // Имя файла обязано соответствовать ключу — иначе запись перенесли или подменили
            require(fileNameFor(key) == file.name) { "Имя записи не совпадает с ключом" }
            val size = input.readInt()
            require(size in 0..input.available()) { "Некорректный размер записи" }
            return key to ByteArray(size).also { input.readFully(it) }
        }
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "niksey_secure_store_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}
