@file:Suppress("UNCHECKED_CAST", "DEPRECATION")
package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.showToast
import com.google.android.material.card.MaterialCardView

class HolderTextMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    private val blocUserMessage: MaterialCardView = view.findViewById(R.id.bloc_user_message)
    private val chatUserMessage: TextView = view.findViewById(R.id.chat_user_message)
    private val chatUserMessageTime: TextView = view.findViewById(R.id.chat_user_message_time)
    private val userReplyPreview: TextView = view.findViewById(R.id.user_reply_preview)

    private val blocReceivedMessage: MaterialCardView = view.findViewById(R.id.bloc_received_message)
    private val chatReceivedMessage: TextView = view.findViewById(R.id.chat_received_message)
    private val chatReceivedMessageTime: TextView = view.findViewById(R.id.chat_received_message_time)
    private val receivedReplyPreview: TextView = view.findViewById(R.id.received_reply_preview)

    override fun drawMessage(view: MessageView) {
        getDecryptedText(view) { decryptedText ->
            if (view.from == CURRENT_UID) {
                blocUserMessage.visibility = View.VISIBLE
                blocReceivedMessage.visibility = View.GONE

                chatUserMessage.text = decryptedText
                chatUserMessageTime.text = view.timeStamp.asTime()

                showReplyPreview(view, userReplyPreview)
            } else {
                blocUserMessage.visibility = View.GONE
                blocReceivedMessage.visibility = View.VISIBLE

                chatReceivedMessage.text = decryptedText
                chatReceivedMessageTime.text = view.timeStamp.asTime()

                showReplyPreview(view, receivedReplyPreview)
            }
        }
    }

    private fun showReplyPreview(messageView: MessageView, previewView: TextView) {
        if (messageView.replyTo.isNullOrEmpty()) {
            previewView.visibility = View.GONE
            return
        }

        previewView.visibility = View.VISIBLE
        previewView.text = "↩️ Ответ на сообщение"

        previewView.setOnClickListener {
            getSingleChatFragment()?.scrollToMessage(messageView.replyTo!!)
        }
    }

    private fun getDecryptedText(view: MessageView, onResult: (String) -> Unit) {
        try {
            if (view.text.isBlank()) {
                onResult("")
                return
            }

            if (view.decryptedText.isNotEmpty()) {
                onResult(view.decryptedText)
                return
            }

            val partnerId = if (view.from == CURRENT_UID) {
                ChatEncryptionManager.currentChatPartnerId ?: view.from
            } else {
                view.from
            }

            val key = ChatEncryptionManager.getChatKey(partnerId)

            if (key != null) {
                val decrypted = ChatEncryptionManager.decryptMessage(view.text, key)
                view.decryptedText = decrypted
                onResult(decrypted)
            } else {
                onResult(view.text) // показываем как есть
            }
        } catch (e: Exception) {
            // Полностью игнорируем ошибку и показываем оригинальный текст
            onResult(view.text)
        }
    }

    override fun onAttach(view: MessageView) {
        val listener = View.OnLongClickListener {
            showMessageOptions(view, it)
            true
        }
        chatUserMessage.setOnLongClickListener(listener)
        chatReceivedMessage.setOnLongClickListener(listener)
    }

    private fun showMessageOptions(messageView: MessageView, anchor: View) {
        val popup = PopupMenu(itemView.context, anchor)
        popup.inflate(R.menu.message_context_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_copy -> {
                    copyToClipboard(messageView.decryptedText.ifEmpty { messageView.text })
                    true
                }
                R.id.menu_delete -> {
                    getSingleChatFragment()?.deleteMessage(messageView.id, messageView.from)
                    true
                }
                R.id.menu_reply -> {
                    replyToMessage(messageView)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun replyToMessage(view: MessageView) {
        val fragment = getSingleChatFragment() ?: return
        val senderName = if (view.from == CURRENT_UID) "Вы" else "Собеседник"
        val previewText = view.decryptedText.ifEmpty { view.text }
        fragment.startReply(view.id, previewText, senderName)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = APP_ACTIVITY.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        showToast("Скопировано")
    }

    private fun getSingleChatFragment(): SingleChatFragment? {
        return APP_ACTIVITY.supportFragmentManager.fragments
            .firstOrNull { it is SingleChatFragment } as? SingleChatFragment
    }

    override fun onDetach() {
        chatUserMessage.setOnLongClickListener(null)
        chatReceivedMessage.setOnLongClickListener(null)
    }

    override fun onRecycled() {}
    override fun getMessageType(): String = "text"
}