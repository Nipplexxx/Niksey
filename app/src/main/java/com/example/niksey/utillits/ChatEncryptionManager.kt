package com.example.niksey.utillits

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Управление ключами бесед.
 *
 * Беседа — строка-идентификатор (см. [privateConversationId] / [groupConversationId]).
 * Для каждой беседы один AES-ключ, который хранится только в памяти:
 *  • личный чат: ECDH-ключ пары (свой приватный ключ из Keystore + публичный ключ собеседника) + HKDF;
 *  • группа: случайный AES-256 ключ. Создатель шифрует его для каждого участника ключом пары
 *    (creator ↔ member) и кладёт в `groups/{id}/keys/{uid}`. Участник распаковывает его тем же способом.
 *
 * Публичные ключи кэшируются на диске — это не секреты.
 */
object ChatEncryptionManager {

    private const val TAG = "ChatEncryptionManager"
    private const val PREFS_NAME = "chat_encryption_cache_v3"
    private const val KEY_PUBLIC_KEYS = "public_keys"

    private const val PREFIX_PRIVATE = "pm"
    private const val PREFIX_GROUP = "gr"

    /** Текст вместо сообщения, которое нельзя расшифровать. Шифротекст никогда не показывается. */
    const val UNREADABLE_TEXT = "🔒 Сообщение недоступно"

    /** userId → публичный ECDH-ключ (Base64). Не секрет. */
    val publicKeyCache = mutableMapOf<String, String>()

    /** Беседа, которую сейчас открыл пользователь: адаптеры используют её для расшифровки. */
    @Volatile
    var currentConversationId: String? = null

    private lateinit var prefs: SharedPreferences

    /** conversationId → ключ беседы (только память). */
    private val chatKeyCache = ConcurrentHashMap<String, SecretKey>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPublicKeysFromDisk()
        Log.d(TAG, "ChatEncryptionManager initialized")
    }

    // ==================== ИДЕНТИФИКАТОРЫ БЕСЕД ====================

    /** Личный чат: порядок участников не важен, поэтому сортируем — одинаково у обеих сторон. */
    fun privateConversationId(userA: String, userB: String): String {
        val (first, second) = listOf(userA, userB).sorted()
        return "$PREFIX_PRIVATE:$first|$second"
    }

    fun groupConversationId(groupId: String): String = "$PREFIX_GROUP:$groupId"

    /** Собеседник личной беседы; null для групп. */
    private fun peerOf(conversationId: String): String? {
        if (!conversationId.startsWith("$PREFIX_PRIVATE:")) return null
        val parts = conversationId.removePrefix("$PREFIX_PRIVATE:").split("|")
        if (parts.size != 2) return null
        return if (parts[0] == CURRENT_UID) parts[1] else parts[0]
    }

    // ==================== КЛЮЧИ БЕСЕД ====================

    /** Ключ беседы из памяти (null, если ещё не загружен). */
    fun getKey(conversationId: String): SecretKey? = chatKeyCache[conversationId]

    /** Ключ беседы: из памяти либо загрузкой/выводом. [onResult] может вызываться не на главном потоке. */
    fun getKeyAsync(conversationId: String, onResult: (SecretKey?) -> Unit) {
        chatKeyCache[conversationId]?.let { onResult(it); return }

        val peer = peerOf(conversationId)
        when {
            peer != null -> getOtherUserPublicKey(peer) { publicKey ->
                val key = publicKey?.takeIf { it.isNotEmpty() }?.let { deriveSafely(it) }
                key?.let { chatKeyCache[conversationId] = it }
                onResult(key)
            }
            conversationId.startsWith("$PREFIX_GROUP:") ->
                unwrapGroupKey(conversationId.removePrefix("$PREFIX_GROUP:"), onResult)
            else -> onResult(null)
        }
    }

    private fun deriveSafely(publicKeyBase64: String): SecretKey? = try {
        EncryptionUtils.deriveChatKey(publicKeyBase64)
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка вывода ключа беседы", e)
        null
    }

    // ==================== ГРУППЫ ====================

    /** Распаковывает ключ группы, выданный создателем. */
    private fun unwrapGroupKey(groupId: String, onResult: (SecretKey?) -> Unit) {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/$groupId").get()
            .addOnSuccessListener { snapshot ->
                val wrapped = snapshot.child(NODE_KEYS).child(CURRENT_UID).getValue(String::class.java)
                val creatorId = snapshot.child(NODE_MEMBERS).children
                    .firstOrNull { it.getValue(String::class.java) == USER_CREATOR }?.key

                if (wrapped.isNullOrEmpty() || creatorId == null) {
                    onResult(null)
                    return@addOnSuccessListener
                }

                getKeyAsync(privateConversationId(CURRENT_UID, creatorId)) { pairKey ->
                    val groupKey = pairKey?.let { key ->
                        try {
                            val base64 = EncryptionUtils.decryptMessage(wrapped, key, groupWrapAad(groupId))
                            SecretKeySpec(Base64.decode(base64, Base64.DEFAULT), "AES")
                        } catch (e: Exception) {
                            Log.e(TAG, "Не удалось распаковать ключ группы", e)
                            null
                        }
                    }
                    groupKey?.let { chatKeyCache[groupConversationId(groupId)] = it }
                    onResult(groupKey)
                }
            }
            .addOnFailureListener { onResult(null) }
    }

    private fun groupWrapAad(groupId: String) = "gk:$groupId"

    // ==================== ШИФРОВАНИЕ ДЛЯ ОТПРАВКИ / РАСШИФРОВКА ====================

    /** Шифрует текст для беседы; null, если ключ беседы ещё не загружен. */
    fun encrypt(conversationId: String, plainText: String): String? =
        chatKeyCache[conversationId]?.let { EncryptionUtils.encryptMessage(plainText, it, conversationId) }

    /**
     * Расшифровывает текст беседы. null — если ключа нет или проверка подлинности не прошла.
     * Шифротекст наружу не возвращается.
     */
    fun decrypt(conversationId: String, encryptedText: String): String? {
        val key = chatKeyCache[conversationId] ?: return null
        return try {
            EncryptionUtils.decryptMessage(encryptedText, key, conversationId)
        } catch (e: Exception) {
            Log.w(TAG, "Сообщение не расшифровано")
            null
        }
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
                Log.e(TAG, "Не удалось загрузить публичный ключ")
                onResult(null)
            }
    }

    fun cachePublicKey(userId: String, publicKeyBase64: String) {
        if (publicKeyBase64.isNotEmpty()) {
            publicKeyCache[userId] = publicKeyBase64
            savePublicKeysToDisk()
        }
    }

    // ==================== КЭШ НА ДИСКЕ ====================

    private fun savePublicKeysToDisk() {
        val json = JSONObject().apply { publicKeyCache.forEach { (id, key) -> put(id, key) } }
        prefs.edit().putString(KEY_PUBLIC_KEYS, json.toString()).apply()
    }

    private fun loadPublicKeysFromDisk() {
        val jsonString = prefs.getString(KEY_PUBLIC_KEYS, null) ?: return
        try {
            val json = JSONObject(jsonString)
            json.keys().forEach { userId -> publicKeyCache[userId] = json.getString(userId) }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось прочитать кэш публичных ключей", e)
        }
    }

    fun clearAndDeleteCache() {
        clear()
        prefs.edit().clear().apply()
    }

    /** Сбрасывает ключи из памяти (вызывается при выходе). */
    fun clear() {
        chatKeyCache.clear()
        publicKeyCache.clear()
        currentConversationId = null
    }

}
