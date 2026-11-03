package com.example.niksey.utillits

import android.util.Base64
import java.security.PublicKey
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование сообщений Niksey.
 *
 * Форматы сообщений:
 *  • v2 — "v2." + Base64(NO_WRAP)(IV[12] || ciphertext+tag).
 *    Текст привязан к беседе через AAD (идентификатор беседы), поэтому сообщение
 *    нельзя переложить в другой чат, даже если ключ совпадает.
 *  • legacy — Base64(IV[12] || ciphertext+tag) без префикса и без AAD.
 *    Только для чтения старых сообщений; новые сообщения так не пишутся.
 *
 * Ключ беседы: ECDH (P-256) по ключу пользователя из Keystore + HKDF-SHA256 — см. [ChatEncryptionManager].
 * Ключи Signal (протокол для личных чатов и групп) живут в utillits/signal.
 */
object EncryptionUtils {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    /** Префикс формата v2. */
    const val FORMAT_V2_PREFIX = "v2."

    // ==================== КЛЮЧИ ====================

    fun publicKeyToBase64(publicKey: PublicKey): String =
        Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)

    fun publicKeyFromBase64(base64: String): PublicKey {
        val decoded = Base64.decode(base64, Base64.DEFAULT)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoded))
    }

    // ==================== ВЫВОД КЛЮЧА ПАРЫ ====================

    /**
     * ECDH с собственным приватным ключом из Keystore + HKDF.
     * Важно: алгоритм не меняется, иначе старые чаты перестанут открываться.
     */
    fun deriveChatKey(otherPublicKeyBase64: String): SecretKey {
        val otherPublicKey = publicKeyFromBase64(otherPublicKeyBase64)
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(UserKeyManager.getECDHPrivateKey())
        keyAgreement.doPhase(otherPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()
        return SecretKeySpec(hkdf(sharedSecret, 32), "AES")
    }

    private fun hkdf(ikm: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec("NikseySalt2026".toByteArray(), "HmacSHA256"))
        val prk = hmac.doFinal(ikm)

        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update("chat_key_v2".toByteArray())
        hmac.update(0x01.toByte())
        return hmac.doFinal().copyOf(length)
    }


    // ==================== ШИФРОВАНИЕ / РАСШИФРОВКА ====================

    /** Шифрует текст в формате v2, привязывая его к [aad] (идентификатор беседы). */
    fun encryptMessage(plainText: String, key: SecretKey, aad: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return FORMAT_V2_PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    /**
     * Расшифровывает v2 (с проверкой [aad]) и legacy-формат (без AAD).
     * Бросает исключение, если сообщение подделано или ключ не подходит.
     */
    fun decryptMessage(encrypted: String, key: SecretKey, aad: String): String {
        return if (encrypted.startsWith(FORMAT_V2_PREFIX)) {
            decryptRaw(encrypted.removePrefix(FORMAT_V2_PREFIX), key, aad.toByteArray(Charsets.UTF_8))
        } else {
            decryptRaw(encrypted, key, null)
        }
    }

    private fun decryptRaw(base64: String, key: SecretKey, aad: ByteArray?): String {
        val combined = Base64.decode(base64, Base64.DEFAULT)
        require(combined.size > GCM_IV_LENGTH) { "Сообщение слишком короткое" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val body = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        if (aad != null) cipher.updateAAD(aad)
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

}
