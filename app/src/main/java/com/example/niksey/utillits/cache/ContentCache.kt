package com.example.niksey.utillits.cache

import com.example.niksey.models.CommonModel
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.signal.SecureKvStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Локальный кэш данных экранов, чтобы открывать чаты мгновенно (как в Telegram):
 *  • список чатов — последний известный состав и превью;
 *  • сообщения каждой беседы — последние N записей (текст остаётся зашифрованным, как в базе);
 *  • профили собеседников и групп — имя и фото в шапке.
 *
 * Всё хранится в [SecureKvStore], то есть в зашифрованном виде. Запись идёт в фоне, чтение — синхронно
 * (записи маленькие, поэтому это быстро).
 */
object ContentCache {

    private const val CHAT_LIST_KEY = "chat_list"
    private const val MESSAGES_LIMIT = 60

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "content-cache") }

    private val store by lazy { SecureKvStore(APP_ACTIVITY, "content_cache") }

    // ==================== список чатов ====================

    fun saveChatList(list: List<CommonModel>) = writeAsync {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        store.put(CHAT_LIST_KEY, array.toString().toByteArray(Charsets.UTF_8))
    }

    fun loadChatList(): List<CommonModel> = read(CHAT_LIST_KEY)?.let { fromJsonArray(it) }.orEmpty()

    // ==================== сообщения беседы ====================

    fun saveMessages(conversationId: String, messages: List<CommonModel>) = writeAsync {
        val array = JSONArray()
        messages.takeLast(MESSAGES_LIMIT).forEach { array.put(toJson(it)) }
        store.put("messages:$conversationId", array.toString().toByteArray(Charsets.UTF_8))
    }

    fun loadMessages(conversationId: String): List<CommonModel> =
        read("messages:$conversationId")?.let { fromJsonArray(it) }.orEmpty()

    // ==================== профили ====================

    fun saveProfile(id: String, model: CommonModel) = writeAsync {
        store.put("profile:$id", toJson(model).toString().toByteArray(Charsets.UTF_8))
    }

    fun loadProfile(id: String): CommonModel? = read("profile:$id")?.let { fromJsonArray("[${String(it, Charsets.UTF_8)}]").firstOrNull() }

    /** Очищает кэш содержимого при выходе из аккаунта. */
    fun clear() = writeAsync { store.clear() }

    // ==================== внутреннее ====================

    private fun writeAsync(block: () -> Unit) {
        writer.execute {
            try {
                block()
            } catch (e: Exception) {
                // Кэш — это ускорение, а не данные: при сбое просто не сохраняем
            }
        }
    }

    private fun read(key: String): ByteArray? = try {
        store.get(key)
    } catch (e: Exception) {
        null
    }

    private fun toJson(model: CommonModel): JSONObject = JSONObject().apply {
        put("id", model.id)
        put("username", model.username)
        put("fullname", model.fullname)
        put("phone", model.phone)
        put("photoUrl", model.photoUrl)
        put("state", model.state)
        put("text", model.text)
        put("type", model.type)
        put("from", model.from)
        put("timeStamp", model.timeStamp.toString())
        put("fileUrl", model.fileUrl)
        put("lastMessage", model.lastMessage)
        put("replyTo", model.replyTo ?: JSONObject.NULL)
        put("duration", model.duration ?: JSONObject.NULL)
    }

    private fun fromJsonArray(bytes: ByteArray): List<CommonModel> =
        fromJsonArray(String(bytes, Charsets.UTF_8))

    private fun fromJsonArray(json: String): List<CommonModel> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CommonModel(
                id = o.optString("id"),
                username = o.optString("username"),
                fullname = o.optString("fullname"),
                phone = o.optString("phone"),
                photoUrl = o.optString("photoUrl", "empty"),
                state = o.optString("state"),
                text = o.optString("text"),
                type = o.optString("type"),
                from = o.optString("from"),
                // Сортировка списка ждёт Long: числовые метки восстанавливаем как числа
                timeStamp = o.optString("timeStamp").let { it.toLongOrNull() ?: it },
                fileUrl = o.optString("fileUrl", "empty"),
                lastMessage = o.optString("lastMessage"),
                replyTo = if (o.isNull("replyTo")) null else o.optString("replyTo"),
                duration = if (o.isNull("duration")) null else o.optString("duration")
            )
        }
    }
}
