package com.example.niksey.ui.viewmodels

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.niksey.R
import com.example.niksey.database.getCommonModel
import com.example.niksey.database.getMessageKey
import com.example.niksey.ui.fragments.message_recycler_view.views.AppViewFactory
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.*
import com.example.niksey.utillits.signal.SecureMessaging
import com.example.niksey.utillits.cache.ContentCache
import com.example.niksey.models.CommonModel
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.launch

/**
 * Сообщения одного чата (личного или группы).
 *
 * Спиннер обновления ([refreshing]) показывается только когда пользователь потянул список
 * или открыл «обновить». Он гаснет, когда ответ пришёл, когда чтение завершилось ошибкой
 * или когда прошло [REFRESH_TIMEOUT_MS] — без ответа он не может крутиться вечно.
 *
 * Каждая загрузка получает номер (generation). Ответ устаревшей загрузки не перезаписывает
 * более новый, а спиннер гасит только самая последняя загрузка.
 */
class ChatViewModel : ViewModel() {

    private val _messages = MutableLiveData<List<MessageView>>(emptyList())
    val messages: LiveData<List<MessageView>> = _messages

    private val _refreshing = MutableLiveData(false)
    /** Идёт обновление по запросу пользователя (pull-to-refresh). */
    val refreshing: LiveData<Boolean> = _refreshing

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshTimeout: Runnable? = null

    private var chatId: String = ""
    private var isGroup: Boolean = false
    private var messageCount = 30

    private var loadGeneration = 0
    private var appliedGeneration = 0

    private fun cacheKey() = (if (isGroup) "gr:" else "pm:") + chatId

    fun initChat(chatId: String, isGroup: Boolean = false) {
        this.chatId = chatId
        this.isGroup = isGroup
        // Сначала — последние сообщения из кэша: экран не пустует, пока идёт загрузка из сети
        _messages.value = ContentCache.loadMessages(cacheKey()).map { AppViewFactory.getView(it) }
        load(userRequested = false)
        attachLiveUpdates()
    }

    /** Обновление по жесту пользователя: спиннер виден до ответа, ошибки или таймаута. */
    fun refresh() = load(userRequested = true)

    /** Перечитать после отправки или вложения: без спиннера. */
    fun reloadMessages() = load(userRequested = false)

    fun loadMoreMessages() {
        messageCount += 20
        refresh()
    }

    fun clearError() {
        _error.value = null
    }

    // ==================== загрузка ====================

    private fun load(userRequested: Boolean) {
        val generation = ++loadGeneration
        if (userRequested) startRefreshing(generation)

        val onError: (DatabaseError) -> Unit = { finishLoad(generation, it) }
        if (isGroup) loadGroupMessages(generation, onError) else loadPrivateMessages(generation, onError)
    }

    private fun loadGroupMessages(generation: Int, onError: (DatabaseError) -> Unit) {
        REF_DATABASE_ROOT.child(NODE_GROUPS).child(chatId).child(NODE_MESSAGES)
            .limitToLast(messageCount)
            .addListenerForSingleValueEvent(AppValueEventListener(onError) { snapshot ->
                val raw = snapshot.children.mapNotNull { it.getCommonModel() }
                applyMessages(generation, raw)
            })
    }

    private fun loadPrivateMessages(generation: Int, onError: (DatabaseError) -> Unit) {
        val path1 = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID).child(chatId)
        val path2 = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(chatId).child(CURRENT_UID)

        path1.limitToLast(messageCount).addListenerForSingleValueEvent(AppValueEventListener(onError) { snap1 ->
            val first = snap1.children.mapNotNull { it.getCommonModel() }
            path2.limitToLast(messageCount).addListenerForSingleValueEvent(AppValueEventListener(onError) { snap2 ->
                applyMessages(generation, first + snap2.children.mapNotNull { it.getCommonModel() })
            })
        })
    }

    /** Показывает загруженные сообщения, если ответ не устарел, и кэширует их. */
    private fun applyMessages(generation: Int, messages: List<CommonModel>) {
        if (generation < appliedGeneration) return
        appliedGeneration = generation

        val sorted = messages.distinctBy { it.id }
            .sortedBy { it.timeStamp.toString().toLongOrNull() ?: 0 }
        ContentCache.saveMessages(cacheKey(), sorted)
        _messages.value = sorted.map { AppViewFactory.getView(it) }
        finishLoad(generation, null)
    }

    /** Завершает загрузку: гасит спиннер (если это последняя загрузка) и показывает ошибку. */
    private fun finishLoad(generation: Int, error: DatabaseError?) {
        if (generation == loadGeneration) stopRefreshing()
        if (error != null) _error.value = error.message
    }

    // ==================== спиннер обновления ====================

    private fun startRefreshing(generation: Int) {
        _refreshing.value = true
        cancelRefreshTimeout()
        refreshTimeout = Runnable {
            // Ответа нет: гасим спиннер и говорим почему, а не крутимся бесконечно
            if (_refreshing.value == true) {
                stopRefreshing()
                _error.value = APP_ACTIVITY.getString(R.string.chat_refresh_timeout)
            }
        }.also { mainHandler.postDelayed(it, REFRESH_TIMEOUT_MS) }
    }

    private fun stopRefreshing() {
        cancelRefreshTimeout()
        _refreshing.value = false
    }

    private fun cancelRefreshTimeout() {
        refreshTimeout?.let { mainHandler.removeCallbacks(it) }
        refreshTimeout = null
    }

    // ==================== отправка ====================

    /**
     * Отправка текста. Текст шифруется ключом беседы; если ключа нет — не отправляем вовсе,
     * а не пишем открытый текст в базу.
     */
    fun sendMessage(text: String, replyTo: String? = null, onSuccess: () -> Unit = {}) {
        if (text.isBlank()) return

        val conversationId = if (isGroup) {
            ChatEncryptionManager.groupConversationId(chatId)
        } else {
            ChatEncryptionManager.privateConversationId(CURRENT_UID, chatId)
        }

        val messageKey = getMessageKey(chatId)
        // Личный чат — протокол Signal; группа — прежняя схема (пока)
        val peer = if (isGroup) null else chatId

        SecureMessaging.encryptText(messageKey, peer, conversationId, text) { encrypted, error ->
            APP_ACTIVITY.runOnUiThread {
                if (encrypted == null) {
                    showToast(APP_ACTIVITY.getString(SecureMessaging.errorMessageRes(error)))
                } else {
                    writeTextMessage(messageKey, encrypted, replyTo, onSuccess)
                }
            }
        }
    }

    private fun writeTextMessage(messageKey: String, encryptedText: String, replyTo: String?, onSuccess: () -> Unit) {
        viewModelScope.launch {

            val messageMap = hashMapOf<String, Any>(
                "id" to messageKey,
                "from" to CURRENT_UID,
                "timeStamp" to ServerValue.TIMESTAMP,
                "text" to encryptedText,
                "type" to TYPE_TEXT,
                "replyTo" to (replyTo ?: "")
            )

            if (isGroup) {
                REF_DATABASE_ROOT.child(NODE_GROUPS)
                    .child(chatId)
                    .child(NODE_MESSAGES)
                    .child(messageKey)
                    .setValue(messageMap)
                    .addOnSuccessListener {
                        onSuccess()
                        reloadMessages()
                    }
                    .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
            } else {
                val path1 = "$NODE_MESSAGES/$CURRENT_UID/$chatId/$messageKey"
                val path2 = "$NODE_MESSAGES/$chatId/$CURRENT_UID/$messageKey"

                REF_DATABASE_ROOT.child(path1).setValue(messageMap)
                    .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
                REF_DATABASE_ROOT.child(path2).setValue(messageMap)
                    .addOnSuccessListener {
                        onSuccess()
                        reloadMessages()
                    }
                    .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
            }
        }
    }

    // ==================== живые обновления ====================

    /**
     * Новые сообщения приходят сами (как в Telegram), без pull-to-refresh:
     * ChildEventListener на сообщения чата. Снимается в onCleared — не течёт.
     */
    private var liveListener: com.google.firebase.database.ChildEventListener? = null
    private var liveRef: com.google.firebase.database.DatabaseReference? = null

    private fun attachLiveUpdates() {
        detachLiveUpdates()

        val ref = if (isGroup) {
            REF_DATABASE_ROOT.child(NODE_GROUPS).child(chatId).child(NODE_MESSAGES)
        } else {
            REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID).child(chatId)
        }

        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                val model = snapshot.getCommonModel()
                val view = AppViewFactory.getView(model)

                val current = _messages.value.orEmpty()
                if (current.any { it.id == view.id }) return

                val updated = (current + view).sortedBy { it.timeStamp.toLongOrNull() ?: 0 }
                _messages.value = updated
            }

            override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {
                val id = snapshot.key ?: return
                _messages.value = _messages.value.orEmpty().filterNot { it.id == id }
            }

            override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
            override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        ref.addChildEventListener(listener)
        liveListener = listener
        liveRef = ref
    }

    private fun detachLiveUpdates() {
        liveListener?.let { liveRef?.removeEventListener(it) }
        liveListener = null
        liveRef = null
    }

    override fun onCleared() {
        detachLiveUpdates()
        cancelRefreshTimeout()
        super.onCleared()
    }

    companion object {
        /** Сколько ждём ответа при обновлении, прежде чем показать ошибку. */
        private const val REFRESH_TIMEOUT_MS = 15_000L
    }
}
