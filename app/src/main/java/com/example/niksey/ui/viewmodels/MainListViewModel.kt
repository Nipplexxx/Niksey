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
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener

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
 * Превью обновляются сами (как в Telegram): на сообщения каждого чата висит живая подписка
 * `limitToLast(UNREAD_WINDOW)`. Новое сообщение меняет превью и счётчик непрочитанных
 * без перечитывания списка. Все живые подписки снимаются в [onCleared] и при удалении чата.
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

    /** Шапки чатов (профиль собеседника или группы): id → модель. */
    private val headers = HashMap<String, CommonModel>()

    /** Последнее окно сообщений чата: id → список. Хранится в памяти — непрочитанные пересчитываются без сети. */
    private val messageWindows = HashMap<String, List<CommonModel>>()

    /** Живые подписки на сообщения чатов: id → запрос и слушатель. Снимаются в [detachLive]. */
    private val liveQueries = HashMap<String, Query>()
    private val liveListeners = HashMap<String, ValueEventListener>()

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
            headers.remove(id)
            messageWindows.remove(id)
            detachLive(id)
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
            (active - keys).forEach { id ->
                active.remove(id)
                items.remove(id)
                headers.remove(id)
                messageWindows.remove(id)
                detachLive(id)
            }
            active.addAll(keys)
            publish()
            _loaded.value = true
        })
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        mainRef.removeEventListener(childListener)
        liveQueries.keys.toList().forEach { detachLive(it) }
        super.onCleared()
    }

    // ==================== загрузка чатов ====================

    /** Запись из main_list: подтягиваем шапку и подписываемся на сообщения чата. */
    private fun upsert(snapshot: DataSnapshot) {
        val id = snapshot.key ?: return
        val isGroup = snapshot.getCommonModel().type == TYPE_GROUP
        active.add(id)
        if (isGroup) loadGroupHeader(id) else loadPrivateHeader(id)
        attachLiveMessages(id, isGroup)
    }

    /** Шапка личного чата: имя, фото и онлайн-статус собеседника. Одноразовое чтение. */
    private fun loadPrivateHeader(id: String) {
        REF_DATABASE_ROOT.child(NODE_USERS).child(id)
            .addListenerForSingleValueEvent(AppValueEventListener({ onPreviewFailed(id, it) }) { userSnap ->
                if (!active.contains(id)) return@AppValueEventListener
                val user = userSnap.getCommonModel()
                val model = items[id] ?: user.copy(id = id, type = TYPE_CHAT).also {
                    if (it.fullname.isEmpty()) it.fullname = it.phone
                }
                model.state = userSnap.child(CHILD_STATE).getValue(String::class.java) ?: ""
                headers[id] = model
                applyWindow(id)
            })
    }

    /** Шапка группы: название и фото. Одноразовое чтение. */
    private fun loadGroupHeader(id: String) {
        REF_DATABASE_ROOT.child(NODE_GROUPS).child(id)
            .addListenerForSingleValueEvent(AppValueEventListener({ onPreviewFailed(id, it) }) { groupSnap ->
                if (!active.contains(id)) return@AppValueEventListener
                val group = groupSnap.getCommonModel()
                val model = items[id] ?: group.copy(id = id, type = TYPE_GROUP)
                headers[id] = model
                applyWindow(id)
            })
    }

    /**
     * Живая подписка на последние сообщения чата. Первый ответ приходит сразу (это начальная
     * загрузка превью), дальше — каждое новое сообщение обновляет превью и непрочитанных само.
     */
    private fun attachLiveMessages(id: String, isGroup: Boolean) {
        if (liveListeners.containsKey(id)) return

        val query = if (isGroup) {
            REF_DATABASE_ROOT.child(NODE_GROUPS).child(id).child(NODE_MESSAGES).limitToLast(UNREAD_WINDOW)
        } else {
            REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID).child(id).limitToLast(UNREAD_WINDOW)
        }

        val listener = AppValueEventListener({ onPreviewFailed(id, it) }) { snapshot ->
            messageWindows[id] = snapshot.children.map { it.getCommonModel() }
            applyWindow(id)
        }

        query.addValueEventListener(listener)
        liveQueries[id] = query
        liveListeners[id] = listener
    }

    /** Снимает живую подписку на сообщения чата (чат удалён или ViewModel уничтожается). */
    private fun detachLive(id: String) {
        val listener = liveListeners.remove(id) ?: return
        liveQueries.remove(id)?.removeEventListener(listener)
    }

    /**
     * Применяет последнее окно сообщений к строке чата: превью, время и счётчик непрочитанных.
     * Работает и как начальная загрузка, и как обновление при новом сообщении.
     */
    private fun applyWindow(id: String) {
        if (!active.contains(id)) return
        val model = items[id] ?: headers[id] ?: return
        val messages = messageWindows[id] ?: return

        val conversationId = if (model.type == TYPE_GROUP) {
            ChatEncryptionManager.groupConversationId(id)
        } else {
            ChatEncryptionManager.privateConversationId(CURRENT_UID, id)
        }

        model.unreadCount = countUnread(messages, lastOpened(id), conversationId)

        val last = messages.lastOrNull()
        if (last == null) {
            model.lastMessage = APP_ACTIVITY.getString(R.string.chat_cleared)
            model.timeStamp = 0L
            store(id, model)
        } else {
            model.timeStamp = last.timeStamp as? Long ?: 0L
            // Превью как в Telegram: свои сообщения с префиксом «Вы: »
            val youPrefix = if (last.from == CURRENT_UID) APP_ACTIVITY.getString(R.string.you_prefix) else ""
            decryptPreview(last, conversationId) { text ->
                model.lastMessage = youPrefix + text
                store(id, model)
            }
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
            type.contains("video") || fileUrl.contains("video") -> {
                onResult("📹 Видео"); return
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

    // ==================== непрочитанные ====================

    /**
     * Чужие сообщения, пришедшие после последнего открытия чата. Метка открытия
     * пишется фрагментом чата при onResume (см. markChatAsRead в чатах).
     * Для открытого сейчас чата счётчик всегда 0: пользователь видит сообщения.
     */
    private fun countUnread(messages: List<CommonModel>, lastOpened: Long, conversationId: String): Int {
        if (ChatEncryptionManager.currentConversationId == conversationId) return 0
        return messages.count { msg ->
            msg.from != CURRENT_UID &&
                    ((msg.timeStamp as? Long) ?: 0L) > lastOpened
        }
    }

    /** Метка последнего открытия чата (0 — чат ещё не открывали, все чужие сообщения новые). */
    private fun lastOpened(chatId: String): Long = APP_ACTIVITY
        .getSharedPreferences(PREFS_LAST_OPENED, android.content.Context.MODE_PRIVATE)
        .getLong(chatId, 0L)

    /**
     * Пересчитать счётчики без сети: вызывается, когда пользователь вернулся из открытого чата
     * (метка «прочитано» изменилась — счётчик должен обнулиться сразу, из окна в памяти).
     */
    fun refreshUnread() {
        active.filter { (items[it]?.unreadCount ?: 0) > 0 }.forEach { applyWindow(it) }
    }

    companion object {
        /** SharedPreferences: id чата → время последнего открытия. */
        const val PREFS_LAST_OPENED = "last_opened_chats"
        /** Сколько последних сообщений читаем для счётчика непрочитанных. */
        private const val UNREAD_WINDOW = 20
    }
}
