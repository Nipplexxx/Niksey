package com.example.niksey.utillits

import javax.crypto.SecretKey

/**
 * Глобальный кэш ключей чатов
 * Позволяет быстро получать ключ чата без повторных вычислений
 */
object ChatKeyCache {

    private val chatKeys = mutableMapOf<String, SecretKey>()
    private val userPublicKeys = mutableMapOf<String, String>() // uid -> publicKey

    fun getChatKey(userId: String, publicKeyBase64: String): SecretKey {
        return chatKeys.getOrPut(userId) {
            EncryptionUtils.deriveChatKey(publicKeyBase64)
        }
    }

    fun saveUserPublicKey(userId: String, publicKeyBase64: String) {
        userPublicKeys[userId] = publicKeyBase64
    }

    fun getUserPublicKey(userId: String): String? {
        return userPublicKeys[userId]
    }

    fun clear() {
        chatKeys.clear()
        userPublicKeys.clear()
    }
}