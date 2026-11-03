package com.example.niksey.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.niksey.database.getCommonModel
import com.example.niksey.database.getMessageKey
import com.example.niksey.ui.fragments.message_recycler_view.views.AppViewFactory
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.*
import com.example.niksey.utillits.signal.SecureMessaging
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val _messages = MutableLiveData<List<MessageView>>(emptyList())
    val messages: LiveData<List<MessageView>> = _messages

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var chatId: String = ""
    private var isGroup: Boolean = false
    private var messageCount = 30

    fun initChat(chatId: String, isGroup: Boolean = false) {
        this.chatId = chatId
        this.isGroup = isGroup

        if (isGroup) {
            loadGroupMessages()
        } else {
            loadPrivateMessagesFromBothPaths()
        }
    }

    private fun loadGroupMessages() {
        _isLoading.value = true
        val ref = REF_DATABASE_ROOT.child(NODE_GROUPS).child(chatId).child(NODE_MESSAGES)

        ref.limitToLast(messageCount).addListenerForSingleValueEvent(
            AppValueEventListener { snapshot ->
                val list = snapshot.children.mapNotNull { it.getCommonModel() }
                    .map { AppViewFactory.getView(it) }
                    .sortedBy { it.timeStamp.toLongOrNull() ?: 0 }

                _messages.value = list
                _isLoading.value = false
            }
        )
    }

    private fun loadPrivateMessagesFromBothPaths() {
        _isLoading.value = true

        val path1 = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID).child(chatId)
        val path2 = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(chatId).child(CURRENT_UID)

        val allMessages = mutableListOf<MessageView>()

        path1.limitToLast(messageCount).addListenerForSingleValueEvent(
            AppValueEventListener { snap1 ->
                allMessages.addAll(snap1.children.mapNotNull { it.getCommonModel() }
                    .map { AppViewFactory.getView(it) })

                path2.limitToLast(messageCount).addListenerForSingleValueEvent(
                    AppValueEventListener { snap2 ->
                        allMessages.addAll(snap2.children.mapNotNull { it.getCommonModel() }
                            .map { AppViewFactory.getView(it) })

                        val unique = allMessages.distinctBy { it.id }
                            .sortedBy { it.timeStamp.toLongOrNull() ?: 0 }

                        _messages.value = unique
                        _isLoading.value = false
                    }
                )
            }
        )
    }

    fun reloadMessages() {
        if (isGroup) {
            loadGroupMessages()
        } else {
            loadPrivateMessagesFromBothPaths()
        }
    }

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

        SecureMessaging.encryptText(messageKey, peer, conversationId, text) { encrypted ->
            if (encrypted == null) {
                APP_ACTIVITY.runOnUiThread { showToast("Сообщение не отправлено: проверьте шифрование чата") }
                return@encryptText
            }
            APP_ACTIVITY.runOnUiThread { writeTextMessage(messageKey, encrypted, replyTo, onSuccess) }
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
            } else {
                val path1 = "$NODE_MESSAGES/$CURRENT_UID/$chatId/$messageKey"
                val path2 = "$NODE_MESSAGES/$chatId/$CURRENT_UID/$messageKey"

                REF_DATABASE_ROOT.child(path1).setValue(messageMap)
                REF_DATABASE_ROOT.child(path2).setValue(messageMap)
                    .addOnSuccessListener {
                        onSuccess()
                        reloadMessages()
                    }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun clearError() {
        _error.value = null
    }

    fun loadMoreMessages() {
        messageCount += 20
        if (isGroup) {
            loadGroupMessages()
        } else {
            loadPrivateMessagesFromBothPaths()
        }
    }
}