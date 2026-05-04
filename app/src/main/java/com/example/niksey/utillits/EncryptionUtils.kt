package com.example.niksey.utillits

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Полноценное End-to-End шифрование для Niksey (Версия 2.0 - Пост-квантовое)
 *
 * Архитектура:
 * - Приватный ECDH ключ — только в Android Keystore (максимальная безопасность)
 * - Публичный ключ — в Firebase + SharedPreferences
 * - Гибридное шифрование: ECDH (P-256) + ML-KEM-768 (Kyber)
 */
object EncryptionUtils {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val USER_KEY_ALIAS = "niksey_user_ecdh_key_v2"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // ==================== ГЕНЕРАЦИЯ КЛЮЧЕЙ ====================

    fun generateUserKeyPair(): PublicKey {
        if (keyStore.containsAlias(USER_KEY_ALIAS)) {
            return getUserPublicKey()!!
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            "EC", ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            USER_KEY_ALIAS,
            KeyProperties.PURPOSE_AGREE_KEY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        keyPairGenerator.initialize(spec)
        return keyPairGenerator.generateKeyPair().public
    }

    fun publicKeyToBase64(publicKey: PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.DEFAULT)
    }

    fun publicKeyFromBase64(base64: String): PublicKey {
        val decoded = Base64.decode(base64, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(decoded)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(keySpec)
    }

    // ==================== ПОЛУЧЕНИЕ КЛЮЧЕЙ ====================

    fun getUserPublicKey(): PublicKey? {
        if (!keyStore.containsAlias(USER_KEY_ALIAS)) return null
        val entry = keyStore.getEntry(USER_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey
    }

    fun getUserPrivateKey(): PrivateKey {
        val entry = keyStore.getEntry(USER_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey
    }

    // ==================== ВЫВОД КЛЮЧА ДЛЯ ЧАТА ====================

    fun deriveChatKey(otherPublicKeyBase64: String): SecretKey {
        val otherPublicKey = publicKeyFromBase64(otherPublicKeyBase64)
        val privateKey = getUserPrivateKey()

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(otherPublicKey, true)

        val sharedSecret = keyAgreement.generateSecret()
        val keyBytes = hkdf(sharedSecret, 32)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun deriveHybridChatKey(
        otherECDHPublicKeyBase64: String,
        otherKyberPublicKeyBase64: String
    ): SecretKey {
        return PostQuantumKeyManager.deriveHybridChatKey(
            otherECDHPublicKeyBase64,
            otherKyberPublicKeyBase64
        )
    }

    private fun hkdf(ikm: ByteArray, length: Int): ByteArray {
        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec("NikseySalt2026".toByteArray(), "HmacSHA256"))
        val prk = hmac.doFinal(ikm)

        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update("chat_key_v2".toByteArray())
        hmac.update(0x01.toByte())
        return hmac.doFinal().copyOf(length)
    }

    // ==================== ШИФРОВАНИЕ / РАСШИФРОВКА ====================

    fun encryptMessage(plainText: String, chatKey: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, chatKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    fun decryptMessage(encryptedText: String, chatKey: SecretKey): String {
        val combined = Base64.decode(encryptedText, Base64.DEFAULT)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, chatKey, spec)

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}