package com.example.niksey.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.niksey.R
import com.example.niksey.database.getCommonModel
import com.example.niksey.models.CommonModel
import com.example.niksey.utillits.*
import com.example.niksey.utillits.cache.ContentCache
import com.example.niksey.utillits.signal.SecureMessaging
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError

/**
 * Список чатов экрана «Чаты».
 *
 * Состояние живёт в ViewModel активности, поэтому не теряется, когда фрагмент уходит в стек
 * (открыт чат или группа) и возвращается обратно: [chats] сразу отдаёт текущий список.
 *
 * Источник правды — Firebase `main_list/{me}`. Подписка через ChildEventListener: добавления,
 * изменения и удаления приходят по одному, поэтому частичный или кэшированный ответ не может
 * удалить чаты. Кэш с диска используется только для мгновенного показа до первого ответа сети.
 *
 * Все изменения [items] происходят на главном потоке (Firebase и колбэки расшифровки приходят туда).
 */
class MainListViewModel : ViewModel() {

    private val _chats = MutableLiveData<List<CommonModel>>(emptyList())
    /** Текущий список в порядке показа (новые сверху). */
    val chats: LiveData<List<CommonModel>> = _chats

    private val _loaded = MutableLiveData(false)
    /** Получен ли хотя бы один ответ сети: только тогда пустой список означает «чатов нет». */
    val loaded: LiveData<Boolean> = _loaded

    private val _error = MutableLiveData<String?>()
    /** Причина ошибки чтения; фрагмент показывает её и вызывает [clearError]. */
    val error: LiveData<String?> = _error

    /** Чаты с готовым превью: id → модель. */
    private val items = LinkedHashMap<String, CommonModel>()

    /** Id, которые сейчас есть в main_list: поздний результат превью для удалённого чата отбрасывается. */
    private val active = HashSet<String>()

    private var started = false

    private val mainRef by lazy { REF_DATABASE_ROOT.child(NODE_MAIN_LIST).child(CURRENT_UID) }

    private val childListener = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = upsert(snapshot)

        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = upsert(snapshot)

        override fun onChildRemoved(snapshot: DataSnapshot) {
            val id = snapshot.key ?: return
            active.remove(id)
            items.remove(id)
            publish()
        }

        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

        override fun onCancelled(error: DatabaseError) {
            _error.value = error.message
        }
    }

    /** Запускает подписку один раз; повторные вызовы при возврате на экран ничего не меняют. */
    fun start() {
        if (started) return
        started = true

        // Мгновенный показ: последний сохранённый список, пока идёт запрос к сети
        val cached = ContentCache.loadChatList()
        if (cached.isNotEmpty()) {
            cached.forEach { items[it.id] = it }
            publish()
        }

        mainRef.addChildEventListener(childListener)
        mainRef.addListenerForSingleValueEvent(AppValueEventListener(::onMainListFailed) { snapshot ->
            // Полный список из сети: убираем кэшированные чаты, которых на сервере уже нет
            val keys = snapshot.children.mapNotNull { it.key }.toSet()
            active.retainAll(keys)
            active.addAll(keys)
            items.keys.retainAll(keys)
            publish()
            _loaded.value = true
        })
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        mainRef.removeEventListener(childListener)
        super.onCleared()
    }

    // ==================== загрузка чатов ====================

    private fun upsert(snapshot: DataSnapshot) {
        val id = snapshot.key ?: return
        val type = snapshot.getCommonModel().type
        active.add(id)
        if (type == TYPE_GROUP) loadGroup(id) else loadPrivateChat(id)
    }

    private fun loadPrivateChat(id: String) {
        REF_DATABASE_ROOT.child(NODE_USERS).child(id)
            .addListenerForSingleValueEvent(AppValueEventListener({ onPreviewFailed(id, it) }) { userSnap ->
                val user = userSnap.getCommonModel()
                val messagesRef = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID).child(id)
                messagesRef.limitToLast(1).addListenerForSingleValueEvent(AppValueEventListener({ onPreviewFailed(id, it) }) { msgSnap ->
                    val last = msgSnap.children.firstOrNull()?.getCommonModel()
                    val conversationId = ChatEncryptionManager.privateConversationId(CURRENT_UID, id)
                    val model = user.copy(id = id, type = TYPE_CHAT).also {
                        if (it.fullname.isEmpty()) it.fullname = it.phone
                    }
                    showPreview(id, model, last, conversationId)
                })
            })
    }

    private fun loadGroup(id: String) {
        val groupRef = REF_DATABASE_ROOT.child(NODE_GROUPS).child(id)
        groupRef.addListenerForSingleValueEvent(AppValueEventListener({ onPreviewFailed(id, it) }) { groupSnap ->
            val group = groupSnap.getCommonModel()
            groupRef.child(NODE_MESSAGES).limitToLast(1)
                .addListenerForSingleValueEvent(AppValueEventListener({ onPreviewFailed(id, it) }) { msgSnap ->
                    val last = msgSnap.children.firstOrNull()?.getCommonModel()
                    val conversationId = ChatEncryptionManager.groupConversationId(id)
                    val model = group.copy(id = id, type = TYPE_GROUP)
                    showPreview(id, model, last, conversationId)
                })
        })
    }

    /**
     * Заполняет превью последнего сообщения. Тип чата (чат/группа) задаётся списком, а не типом
     * сообщения: иначе группа с последним текстовым сообщением открывалась бы как личный чат.
     */
    private fun showPreview(id: String, model: CommonModel, last: CommonModel?, conversationId: String) {
        if (last == null) {
            model.lastMessage = APP_ACTIVITY.getString(R.string.chat_cleared)
            model.timeStamp = 0L
            store(id, model)
            return
        }
        decryptPreview(last, conversationId) { text ->
            model.lastMessage = text
            model.timeStamp = last.timeStamp as? Long ?: 0L
            store(id, model)
        }
    }

    private fun onPreviewFailed(id: String, error: DatabaseError) {
        // Превью одного чата не должно ломать весь список: показываем его без нового текста
        if (items.containsKey(id)) return
        _error.value = error.message
    }

    private fun onMainListFailed(error: DatabaseError) {
        _error.value = error.message
        _loaded.value = true
    }

    private fun store(id: String, model: CommonModel) {
        // Чат могли удалить, пока шёл запрос: тогда результат не возвращаем
        if (!active.contains(id)) return
        items[id] = model
        publish()
    }

    private fun publish() {
        val sorted = items.values.sortedByDescending { it.timeStamp as? Long ?: 0L }
        _chats.value = sorted
        ContentCache.saveChatList(sorted)
    }

    // ==================== текст превью ====================

    private fun decryptPreview(msg: CommonModel, conversationId: String, onResult: (String) -> Unit) {
        val type = msg.type.lowercase()
        val fileUrl = msg.fileUrl.lowercase()

        when {
            type.contains("voice") || fileUrl.contains("voice") -> {
                onResult("🎤 Голосовое сообщение"); return
            }
            type.contains("image") || type.contains("photo") || fileUrl.contains("image") || fileUrl.contains("photo") -> {
                onResult("🖼️ Изображение"); return
            }
            type.contains("file") || fileUrl.contains("file") -> {
                onResult("📎 Файл"); return
            }
        }

        if (msg.text.isBlank()) {
            onResult("Нет сообщений"); return
        }
        if (msg.decryptedText.isNotEmpty()) {
            onResult(msg.decryptedText); return
        }

        fun preview(decrypted: String?): String = when {
            decrypted == null -> ChatEncryptionManager.UNREADABLE_TEXT
            decrypted.length > 50 -> decrypted.take(50) + "..."
            else -> decrypted
        }

        SecureMessaging.decryptText(msg.id, msg.from, conversationId, msg.text) { decrypted ->
            if (decrypted != null) msg.decryptedText = decrypted
            onResult(preview(decrypted))
        }
    }
}
