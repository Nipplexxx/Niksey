package com.example.niksey.utillits

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.example.niksey.database.CURRENT_UID
import com.example.niksey.database.CHILD_PUBLIC_KEY
import com.example.niksey.database.NODE_USERS
import com.example.niksey.database.REF_DATABASE_ROOT
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Менеджер шифрования чатов (Версия 2.1 — исправленная)
 *
 * Основные исправления:
 * - Убраны все синхронные Firebase вызовы (.get().result)
 * - Приоритет гибридному пост-квантовому шифрованию (ECDH + Kyber)
 * - Надёжное кэширование ключей
 * - Безопасный fallback
 * - Улучшено логирование
 */
object ChatEncryptionManager {

    private const val TAG = "ChatEncryptionManager"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CHAT_KEY_PREFIX = "niksey_chat_key_"
    private const val PREFS_NAME = "chat_encryption_cache_v2"
    private const val KEY_PUBLIC_KEYS = "public_keys"
    private const val KEY_KYBER_PUBLIC_KEYS = "kyber_public_keys"

    val publicKeyCache = mutableMapOf<String, String>()
    val kyberPublicKeyCache = mutableMapOf<String, String>()
    var currentChatPartnerId: String? = null
    var currentEncryptionVersion: Int = 2 // 2 = гибридное пост-квантовое

    private lateinit var prefs: SharedPreferences
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPublicKeysFromDisk()
        loadKyberPublicKeysFromDisk()
        Log.d(TAG, "ChatEncryptionManager initialized. Cached users: ${publicKeyCache.size}")
    }

    // ==================== ПОЛУЧЕНИЕ КЛЮЧА ЧАТА ====================

    /**
     * Главный метод получения ключа чата.
     * Сначала проверяет Keystore → потом кэш → приоритет гибридному шифрованию.
     * Синхронная загрузка из Firebase УБРАНА (делается асинхронно из UI-слоя).
     */
    fun getOrCreateChatKey(otherUserId: String): SecretKey {
        val alias = getChatKeyAlias(otherUserId)

        // 1. Уже есть готовый ключ в Keystore
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as SecretKey
        }

        val ecdhKey = publicKeyCache[otherUserId]
        val kyberKey = kyberPublicKeyCache[otherUserId]

        // 2. Гибридное пост-квантовое шифрование (рекомендуется)
        if (!ecdhKey.isNullOrEmpty() && !kyberKey.isNullOrEmpty()) {
            return try {
                EncryptionUtils.deriveHybridChatKey(ecdhKey, kyberKey)
            } catch (e: Exception) {
                Log.e(TAG, "Hybrid derivation failed for $otherUserId", e)
                deriveClassicKey(ecdhKey, alias)
            }
        }

        // 3. Классическое ECDH (fallback)
        if (!ecdhKey.isNullOrEmpty()) {
            return deriveClassicKey(ecdhKey, alias)
        }

        // 4. Полный fallback — генерируем случайный AES-ключ
        Log.w(TAG, "No public key found for $otherUserId. Generating temporary AES key.")
        return generateTemporaryAESKey(alias)
    }

    private fun deriveClassicKey(publicKeyBase64: String, alias: String): SecretKey {
        return try {
            EncryptionUtils.deriveChatKey(publicKeyBase64)
        } catch (e: Exception) {
            Log.e(TAG, "Classic ECDH derivation failed", e)
            generateTemporaryAESKey(alias)
        }
    }

    private fun generateTemporaryAESKey(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    fun getChatKey(otherUserId: String): SecretKey? {
        val effectiveId = if (otherUserId == CURRENT_UID && currentChatPartnerId != null) {
            currentChatPartnerId!!
        } else otherUserId

        val alias = getChatKeyAlias(effectiveId)
        return if (keyStore.containsAlias(alias)) {
            keyStore.getKey(alias, null) as? SecretKey
        } else null
    }

    fun deleteChatKey(otherUserId: String) {
        val alias = getChatKeyAlias(otherUserId)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        publicKeyCache.remove(otherUserId)
        kyberPublicKeyCache.remove(otherUserId)
    }

    private fun getChatKeyAlias(otherUserId: String): String =
        "$CHAT_KEY_PREFIX${CURRENT_UID}_$otherUserId"

    // ==================== КЭШИРОВАНИЕ ПУБЛИЧНЫХ КЛЮЧЕЙ ====================

    fun cachePublicKey(userId: String, publicKeyBase64: String) {
        if (publicKeyBase64.isNotEmpty()) {
            publicKeyCache[userId] = publicKeyBase64
            savePublicKeysToDisk()
        }
    }

    fun cacheKyberPublicKey(userId: String, kyberPublicKeyBase64: String) {
        if (kyberPublicKeyBase64.isNotEmpty()) {
            kyberPublicKeyCache[userId] = kyberPublicKeyBase64
            saveKyberPublicKeysToDisk()
        }
    }

    // Асинхронные методы загрузки (оставлены как были — они правильные)
    fun getOtherUserPublicKey(otherUserId: String, onResult: (String?) -> Unit) {
        publicKeyCache[otherUserId]?.let {
            onResult(it)
            return
        }

        REF_DATABASE_ROOT.child("users/$otherUserId/publicKey")
            .get()
            .addOnSuccessListener { snapshot ->
                val key = snapshot.value as? String
                if (!key.isNullOrEmpty()) cachePublicKey(otherUserId, key)
                onResult(key)
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to load public key for $otherUserId", it)
                onResult(null)
            }
    }

    fun getOtherUserKyberPublicKey(otherUserId: String, onResult: (String?) -> Unit) {
        kyberPublicKeyCache[otherUserId]?.let {
            onResult(it)
            return
        }

        REF_DATABASE_ROOT.child("users/$otherUserId/kyberPublicKey")
            .get()
            .addOnSuccessListener { snapshot ->
                val key = snapshot.value as? String
                if (!key.isNullOrEmpty()) cacheKyberPublicKey(otherUserId, key)
                onResult(key)
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to load Kyber public key for $otherUserId", it)
                onResult(null)
            }
    }

    fun saveUserPublicKey(publicKeyBase64: String) {
        REF_DATABASE_ROOT.child("users/$CURRENT_UID/publicKey").setValue(publicKeyBase64)
        cachePublicKey(CURRENT_UID, publicKeyBase64)
    }

    fun saveUserKyberPublicKey(kyberPublicKeyBase64: String) {
        REF_DATABASE_ROOT.child("users/$CURRENT_UID/kyberPublicKey").setValue(kyberPublicKeyBase64)
        cacheKyberPublicKey(CURRENT_UID, kyberPublicKeyBase64)
    }

    // ==================== ШИФРОВАНИЕ / РАСШИФРОВКА ====================

    fun decryptMessage(encryptedText: String, chatKey: SecretKey): String {
        return try {
            EncryptionUtils.decryptMessage(encryptedText, chatKey)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            encryptedText.take(30) + "... [decryption error]"
        }
    }

    // ==================== СОХРАНЕНИЕ / ЗАГРУЗКА КЭША ====================

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
            Log.e(TAG, "Failed to load public keys cache", e)
            prefs.edit().remove(KEY_PUBLIC_KEYS).apply()
        }
    }

    private fun saveKyberPublicKeysToDisk() {
        val json = JSONObject().apply {
            kyberPublicKeyCache.forEach { (id, key) -> put(id, key) }
        }
        prefs.edit().putString(KEY_KYBER_PUBLIC_KEYS, json.toString()).apply()
    }

    private fun loadKyberPublicKeysFromDisk() {
        val jsonString = prefs.getString(KEY_KYBER_PUBLIC_KEYS, null) ?: return
        try {
            val json = JSONObject(jsonString)
            json.keys().forEach { userId ->
                kyberPublicKeyCache[userId] = json.getString(userId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Kyber keys cache", e)
            prefs.edit().remove(KEY_KYBER_PUBLIC_KEYS).apply()
        }
    }

    // ==================== ОЧИСТКА ====================

    fun clearAndDeleteCache() {
        keyStore.aliases().toList().forEach { alias ->
            if (alias.startsWith(CHAT_KEY_PREFIX)) {
                try { keyStore.deleteEntry(alias) } catch (e: Exception) {}
            }
        }
        publicKeyCache.clear()
        kyberPublicKeyCache.clear()
        currentChatPartnerId = null

        prefs.edit()
            .remove(KEY_PUBLIC_KEYS)
            .remove(KEY_KYBER_PUBLIC_KEYS)
            .apply()
    }

    fun clear() {
        publicKeyCache.clear()
        kyberPublicKeyCache.clear()
        currentChatPartnerId = null
    }
}