package com.example.niksey.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.niksey.database.getCommonModel
import com.example.niksey.database.sendMessage
import com.example.niksey.database.sendMessageToGroup
import com.example.niksey.ui.fragments.message_recycler_view.views.AppViewFactory
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.AppValueEventListener
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.NODE_GROUPS
import com.example.niksey.utillits.NODE_MESSAGES
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.TYPE_TEXT
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.launch

/**
 * ViewModel для чатов (SingleChat + GroupChat)
 * Сохраняет состояние при повороте экрана и упрощает фрагменты
 */
class ChatViewModel : ViewModel() {

    private val _messages = MutableLiveData<List<MessageView>>(emptyList())
    val messages: LiveData<List<MessageView>> = _messages

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var chatId: String = ""
    private var isGroup: Boolean = false
    private var mRefMessages: DatabaseReference? = null
    private var messageCount = 20

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================

    fun initChat(chatId: String, isGroup: Boolean = false) {
        this.chatId = chatId
        this.isGroup = isGroup

        mRefMessages = if (isGroup) {
            REF_DATABASE_ROOT.child(NODE_GROUPS).child(chatId).child(NODE_MESSAGES)
        } else {
            REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID).child(chatId)
        }

        loadMessages()
    }

    // ==================== ЗАГРУЗКА СООБЩЕНИЙ ====================

    private fun loadMessages() {
        _isLoading.value = true

        mRefMessages?.limitToLast(messageCount)?.addListenerForSingleValueEvent(
            AppValueEventListener { snapshot ->
                val list = snapshot.children.mapNotNull { it.getCommonModel() }
                    .map { AppViewFactory.getView(it) }

                _messages.value = list
                _isLoading.value = false
            }
        )
    }

    fun loadMoreMessages() {
        messageCount += 20
        loadMessages()
    }

    /** Перезагружает сообщения (для обновления после отправки медиа) */
    fun reloadMessages() {
        loadMessages()
    }

    // ==================== ОТПРАВКА СООБЩЕНИЙ ====================

    fun sendMessage(text: String, onSuccess: () -> Unit = {}) {
        if (text.isBlank()) return

        viewModelScope.launch {
            if (isGroup) {
                sendMessageToGroup(text, chatId, TYPE_TEXT) {
                    onSuccess()
                    // Перезагружаем сообщения, чтобы сразу показать отправленное
                    loadMessages()
                }
            } else {
                sendMessage(text, chatId, TYPE_TEXT) {
                    onSuccess()
                    // Перезагружаем сообщения, чтобы сразу показать отправленное
                    loadMessages()
                }
            }
        }
    }

    // ==================== ОЧИСТКА ====================

    override fun onCleared() {
        super.onCleared()
        // Можно добавить отписку от слушателей, если нужно
    }

    fun clearError() {
        _error.value = null
    }
}