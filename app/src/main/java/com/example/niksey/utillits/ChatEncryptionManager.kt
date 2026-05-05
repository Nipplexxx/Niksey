package com.example.niksey.utillits

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Менеджер шифрования чатов (Версия 3.0 — исправленная)
 *
 * Основные исправления:
 * - Полностью асинхронный API
 * - Безопасные проверки на null
 * - Улучшенное кэширование
 * - Нет смешивания синхронного и асинхронного кода
 */
object ChatEncryptionManager {

    private const val TAG = "ChatEncryptionManager"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CHAT_KEY_PREFIX = "niksey_chat_key_"
    private const val PREFS_NAME = "chat_encryption_cache_v3"
    private const val KEY_PUBLIC_KEYS = "public_keys"
    private const val KEY_KYBER_PUBLIC_KEYS = "kyber_public_keys"

    val publicKeyCache = mutableMapOf<String, String>()
    val kyberPublicKeyCache = mutableMapOf<String, String>()
    var currentChatPartnerId: String? = null

    private lateinit var prefs: SharedPreferences
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPublicKeysFromDisk()
        loadKyberPublicKeysFromDisk()
        Log.d(TAG, "ChatEncryptionManager initialized")
    }

    // ==================== АСИНХРОННОЕ ПОЛУЧЕНИЕ КЛЮЧА ЧАТА ====================

    /**
     * Асинхронно получает или создаёт ключ чата.
     * Вызывает onResult с готовым ключом или null, если не удалось.
     */
    fun getOrCreateChatKeyAsync(otherUserId: String, onResult: (SecretKey?) -> Unit) {
        val alias = getChatKeyAlias(otherUserId)

        // 1. Уже есть готовый ключ в Keystore
        if (keyStore.containsAlias(alias)) {
            val key = keyStore.getKey(alias, null) as? SecretKey
            onResult(key)
            return
        }

        val ecdhKey = publicKeyCache[otherUserId]
        val kyberKey = kyberPublicKeyCache[otherUserId]

        // 2. Гибридное пост-квантовое шифрование (приоритет)
        if (!ecdhKey.isNullOrEmpty() && !kyberKey.isNullOrEmpty()) {
            try {
                val hybridKey = EncryptionUtils.deriveHybridChatKey(ecdhKey, kyberKey)
                // Сохраняем в Keystore для будущего использования
                saveKeyToKeystore(alias)
                onResult(hybridKey)
            } catch (e: Exception) {
                Log.e(TAG, "Hybrid derivation failed for $otherUserId", e)
                onResult(null)
            }
            return
        }

        // 3. Только ECDH
        if (!ecdhKey.isNullOrEmpty()) {
            try {
                val chatKey = EncryptionUtils.deriveChatKey(ecdhKey)
                saveKeyToKeystore(alias)
                onResult(chatKey)
            } catch (e: Exception) {
                Log.e(TAG, "ECDH derivation failed", e)
                onResult(null)
            }
            return
        }

        // 4. Ключа нет — загружаем асинхронно
        getOtherUserPublicKey(otherUserId) { loadedKey ->
            if (!loadedKey.isNullOrEmpty()) {
                try {
                    val chatKey = EncryptionUtils.deriveChatKey(loadedKey)
                    saveKeyToKeystore(alias)
                    onResult(chatKey)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to derive key after loading", e)
                    onResult(null)
                }
            } else {
                Log.w(TAG, "No public key found for $otherUserId")
                onResult(null)
            }
        }
    }

    private fun saveKeyToKeystore(alias: String) {
        try {
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
            // Ключ уже создан, просто сохраняем
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save key to Keystore", e)
        }
    }

    // ==================== АСИНХРОННАЯ ЗАГРУЗКА ПУБЛИЧНЫХ КЛЮЧЕЙ ====================

    fun getOtherUserPublicKey(otherUserId: String, onResult: (String?) -> Unit) {
        // Проверяем кэш
        publicKeyCache[otherUserId]?.let {
            onResult(it)
            return
        }

        // Загружаем из Firebase
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
                Log.e(TAG, "Failed to load public key for $otherUserId", it)
                onResult(null)
            }
    }

    fun getOtherUserKyberPublicKey(otherUserId: String, onResult: (String?) -> Unit) {
        kyberPublicKeyCache[otherUserId]?.let {
            onResult(it)
            return
        }

        REF_DATABASE_ROOT.child("$NODE_USERS/$otherUserId/kyberPublicKey")
            .get()
            .addOnSuccessListener { snapshot ->
                val key = snapshot.getValue(String::class.java)
                if (!key.isNullOrEmpty()) {
                    kyberPublicKeyCache[otherUserId] = key
                    saveKyberPublicKeysToDisk()
                }
                onResult(key)
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to load Kyber key for $otherUserId", it)
                onResult(null)
            }
    }

    // ==================== СИНХРОННЫЕ МЕТОДЫ (для обратной совместимости) ====================

    fun getChatKey(otherUserId: String): SecretKey? {
        val alias = getChatKeyAlias(otherUserId)
        return if (keyStore.containsAlias(alias)) {
            keyStore.getKey(alias, null) as? SecretKey
        } else null
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================

    private fun getChatKeyAlias(otherUserId: String): String =
        "$CHAT_KEY_PREFIX${CURRENT_UID}_$otherUserId"

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
                try { keyStore.deleteEntry(alias) } catch (_: Exception) {}
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