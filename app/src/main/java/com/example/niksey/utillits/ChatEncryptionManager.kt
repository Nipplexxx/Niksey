package com.example.niksey.utillits

import android.content.Context
import android.content.SharedPreferences
import com.example.niksey.database.CURRENT_UID
import com.example.niksey.database.REF_DATABASE_ROOT
import org.json.JSONObject
import javax.crypto.SecretKey

object ChatEncryptionManager {

    private val chatKeysCache = mutableMapOf<String, SecretKey>()
    private val publicKeyCache = mutableMapOf<String, String>()
    var currentChatPartnerId: String? = null

    private const val PREFS_NAME = "chat_encryption_cache"
    private const val KEY_PUBLIC_KEYS = "public_keys"
    private lateinit var prefs: SharedPreferences

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromDisk()
    }

    // ==================== РАБОТА С КЭШЕМ ====================
    fun getChatKey(otherUserId: String): SecretKey? {
        val effectiveId = if (otherUserId == CURRENT_UID && currentChatPartnerId != null) {
            currentChatPartnerId!!
        } else {
            otherUserId
        }

        val cacheKey = "$CURRENT_UID-$effectiveId"
        chatKeysCache[cacheKey]?.let { return it }

        val publicKeyBase64 = publicKeyCache[effectiveId] ?: return null

        val secretKey = EncryptionUtils.deriveChatKey(publicKeyBase64)
        chatKeysCache[cacheKey] = secretKey
        return secretKey
    }

    fun cachePublicKey(userId: String, publicKeyBase64: String) {
        if (publicKeyBase64.isNotEmpty()) {
            publicKeyCache[userId] = publicKeyBase64
            saveToDisk()
        }
    }

    fun getOtherUserPublicKey(otherUserId: String, onResult: (String?) -> Unit) {
        publicKeyCache[otherUserId]?.let {
            onResult(it)
            return
        }

        REF_DATABASE_ROOT.child("users/$otherUserId/publicKey")
            .get()
            .addOnSuccessListener { snapshot ->
                val key = snapshot.value as? String
                if (!key.isNullOrEmpty()) {
                    cachePublicKey(otherUserId, key)
                }
                onResult(key)
            }
            .addOnFailureListener {
                onResult(null)
            }
    }

    fun decryptMessage(encryptedText: String, chatKey: SecretKey): String {
        return try {
            EncryptionUtils.decryptMessage(encryptedText, chatKey)
        } catch (e: Exception) {
            encryptedText
        }
    }

    // ==================== СОХРАНЕНИЕ / ЗАГРУЗКА ====================
    private fun saveToDisk() {
        val jsonObject = JSONObject()
        publicKeyCache.forEach { (userId, key) ->
            jsonObject.put(userId, key)
        }
        prefs.edit().putString(KEY_PUBLIC_KEYS, jsonObject.toString()).apply()
    }

    private fun loadFromDisk() {
        val jsonString = prefs.getString(KEY_PUBLIC_KEYS, null) ?: return
        try {
            val jsonObject = JSONObject(jsonString)
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val userId = keys.next()
                val publicKey = jsonObject.getString(userId)
                publicKeyCache[userId] = publicKey
            }
        } catch (e: Exception) {
            // Если файл повреждён — очищаем
            prefs.edit().remove(KEY_PUBLIC_KEYS).apply()
        }
    }

    // ==================== ОЧИСТКА ====================
    fun clear() {
        chatKeysCache.clear()
        publicKeyCache.clear()
        currentChatPartnerId = null
    }

    fun clearAndDeleteCache() {
        clear()
        prefs.edit().remove(KEY_PUBLIC_KEYS).apply()
    }
}