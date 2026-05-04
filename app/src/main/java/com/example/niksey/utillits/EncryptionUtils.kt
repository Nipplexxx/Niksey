package com.example.niksey.utillits

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.example.niksey.database.CURRENT_UID
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Полноценное End-to-End шифрование для Niksey
 * ECDH (P-256) + HKDF + AES-256-GCM
 */
object EncryptionUtils {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val USER_KEY_ALIAS = "niksey_user_ecdh_key"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    // ==================== ГЕНЕРАЦИЯ КЛЮЧЕЙ ====================

    fun generateUserKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        val parameterSpec = ECGenParameterSpec("secp256r1")
        keyPairGenerator.initialize(parameterSpec)
        return keyPairGenerator.generateKeyPair()
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

    fun privateKeyFromBase64(base64: String): PrivateKey {
        val decoded = Base64.decode(base64, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(decoded)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(keySpec)
    }

    // ==================== СОХРАНЕНИЕ КЛЮЧЕЙ ====================

    fun saveUserKeyPair(keyPair: KeyPair) {
        val prefs = APP_ACTIVITY.getSharedPreferences("niksey_keys", Context.MODE_PRIVATE)
        prefs.edit {
            // === ПРАВИЛЬНО: сохраняем именно приватный ключ ===
            putString("private_key_$CURRENT_UID", Base64.encodeToString(keyPair.private.encoded, Base64.DEFAULT))
            putString("public_key_$CURRENT_UID", publicKeyToBase64(keyPair.public))
        }
    }

    // ==================== ПОЛУЧЕНИЕ КЛЮЧЕЙ ====================

    fun getUserPublicKey(): PublicKey? {
        val prefs = APP_ACTIVITY.getSharedPreferences("niksey_keys", Context.MODE_PRIVATE)
        val publicKeyBase64 = prefs.getString("public_key_$CURRENT_UID", null)
        return if (publicKeyBase64 != null) {
            publicKeyFromBase64(publicKeyBase64)
        } else {
            null
        }
    }

    fun getUserPrivateKey(userId: String): PrivateKey? {
        val prefs = APP_ACTIVITY.getSharedPreferences("niksey_keys", Context.MODE_PRIVATE)
        val privateKeyBase64 = prefs.getString("private_key_$userId", null)

        return if (!privateKeyBase64.isNullOrEmpty()) {
            try {
                privateKeyFromBase64(privateKeyBase64)
            } catch (e: Exception) {
                // Если ключ битый — пересоздаём
                val newKeyPair = generateUserKeyPair()
                saveUserKeyPair(newKeyPair)
                newKeyPair.private
            }
        } else {
            val keyPair = generateUserKeyPair()
            saveUserKeyPair(keyPair)
            keyPair.private
        }
    }

    // ==================== ВЫВОД КЛЮЧА ДЛЯ ЧАТА ====================

    fun deriveChatKey(otherPublicKeyBase64: String): SecretKey {
        val otherPublicKey = publicKeyFromBase64(otherPublicKeyBase64)
        val privateKey = getUserPrivateKey(CURRENT_UID)
            ?: throw IllegalStateException("Не удалось получить приватный ключ пользователя")

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(otherPublicKey, true)

        val sharedSecret = keyAgreement.generateSecret()
        val keyBytes = hkdf(sharedSecret, 32)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun hkdf(ikm: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec("NikseySalt".toByteArray(), "HmacSHA256"))
        val prk = hmac.doFinal(ikm)

        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update("chat_key".toByteArray())
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