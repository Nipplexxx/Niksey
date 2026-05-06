package com.example.niksey.utillits

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

object ChatEncryptionManager {

    private const val TAG = "ChatEncryptionManager"
    private const val PREFS_NAME = "chat_encryption_cache_v3"
    private const val KEY_PUBLIC_KEYS = "public_keys"
    private const val KEY_KYBER_PUBLIC_KEYS = "kyber_public_keys"

    val publicKeyCache = mutableMapOf<String, String>()
    val kyberPublicKeyCache = mutableMapOf<String, String>()
    var currentChatPartnerId: String? = null

    private lateinit var prefs: SharedPreferences

    // Кэш ключей чатов в памяти (самое надёжное решение)
    private val chatKeyCache = mutableMapOf<String, SecretKey>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPublicKeysFromDisk()
        loadKyberPublicKeysFromDisk()
        Log.d(TAG, "ChatEncryptionManager initialized")
    }

    // ==================== ГЛАВНЫЙ МЕТОД ====================

    fun getOrCreateChatKeyAsync(otherUserId: String, onResult: (SecretKey?) -> Unit) {
        val cacheKey = getChatCacheKey(CURRENT_UID, otherUserId)

        // 1. Уже есть в памяти
        chatKeyCache[cacheKey]?.let {
            onResult(it)
            return
        }

        val ecdhKey = publicKeyCache[otherUserId]
        val kyberKey = kyberPublicKeyCache[otherUserId]

        // 2. Гибридный ключ (ECDH + Kyber)
        if (!ecdhKey.isNullOrEmpty() && !kyberKey.isNullOrEmpty()) {
            try {
                val hybridKey = EncryptionUtils.deriveHybridChatKey(ecdhKey, kyberKey)
                chatKeyCache[cacheKey] = hybridKey
                onResult(hybridKey)
            } catch (e: Exception) {
                Log.e(TAG, "Hybrid key derivation failed", e)
                onResult(null)
            }
            return
        }

        // 3. Только ECDH
        if (!ecdhKey.isNullOrEmpty()) {
            try {
                val chatKey = EncryptionUtils.deriveChatKey(ecdhKey)
                chatKeyCache[cacheKey] = chatKey
                onResult(chatKey)
            } catch (e: Exception) {
                Log.e(TAG, "ECDH derivation failed", e)
                onResult(null)
            }
            return
        }

        // 4. Загружаем публичный ключ
        getOtherUserPublicKey(otherUserId) { loadedKey ->
            if (!loadedKey.isNullOrEmpty()) {
                try {
                    val chatKey = EncryptionUtils.deriveChatKey(loadedKey)
                    chatKeyCache[cacheKey] = chatKey
                    onResult(chatKey)
                } catch (e: Exception) {
                    Log.e(TAG, "Derive after load failed", e)
                    onResult(null)
                }
            } else {
                onResult(null)
            }
        }
    }

    fun getChatKey(otherUserId: String): SecretKey? {
        val cacheKey = getChatCacheKey(CURRENT_UID, otherUserId)
        return chatKeyCache[cacheKey]
    }

    private fun getChatCacheKey(user1: String, user2: String): String {
        val sorted = listOf(user1, user2).sorted()
        return "${sorted[0]}_${sorted[1]}"
    }

    // ==================== ПУБЛИЧНЫЕ КЛЮЧИ ====================

    fun getOtherUserPublicKey(otherUserId: String, onResult: (String?) -> Unit) {
        publicKeyCache[otherUserId]?.let { onResult(it); return }

        REF_DATABASE_ROOT.child("$NODE_USERS/$otherUserId/$CHILD_PUBLIC_KEY")
            .get()
            .addOnSuccessListener { snapshot ->
                val key = snapshot.getValue(String::class.java)
                if (!key.isNullOrEmpty()) {
                    publicKeyCache[otherUserId] = key
                    savePublicKeysToDisk()
                }
                onResult(key)
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to load public key", it)
                onResult(null)
            }
    }

    fun cachePublicKey(userId: String, publicKeyBase64: String) {
        if (publicKeyBase64.isNotEmpty()) {
            publicKeyCache[userId] = publicKeyBase64
            savePublicKeysToDisk()
        }
    }

    fun decryptMessage(encryptedText: String, secretKey: SecretKey): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(encryptedText.take(24), Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(encryptedText.substring(24), Base64.NO_WRAP)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(encryptedBytes)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            // Полностью подавляем ошибку
            encryptedText
        }
    }

    // ==================== СОХРАНЕНИЕ КЭША ====================

    private fun savePublicKeysToDisk() {
        val json = JSONObject().apply {
            publicKeyCache.forEach { (id, key) -> put(id, key) }
        }
        prefs.edit().putString(KEY_PUBLIC_KEYS, json.toString()).apply()
    }

    private fun loadPublicKeysFromDisk() {
        val jsonString = prefs.getString(KEY_PUBLIC_KEYS, null) ?: return
        try {
            val json = JSONObject(jsonString)
            json.keys().forEach { userId ->
                publicKeyCache[userId] = json.getString(userId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load public keys", e)
        }
    }

    private fun loadKyberPublicKeysFromDisk() {
        val jsonString = prefs.getString(KEY_KYBER_PUBLIC_KEYS, null) ?: return
        try {
            val json = JSONObject(jsonString)
            json.keys().forEach { userId ->
                kyberPublicKeyCache[userId] = json.getString(userId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Kyber keys", e)
        }
    }

    fun clearAndDeleteCache() {
        chatKeyCache.clear()
        publicKeyCache.clear()
        kyberPublicKeyCache.clear()
        currentChatPartnerId = null
        prefs.edit().clear().apply()
    }

    fun clear() {
        chatKeyCache.clear()
        publicKeyCache.clear()
        kyberPublicKeyCache.clear()
        currentChatPartnerId = null
    }
}