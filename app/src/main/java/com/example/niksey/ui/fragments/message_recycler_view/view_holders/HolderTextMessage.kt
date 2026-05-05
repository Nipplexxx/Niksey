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
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.CHILD_PUBLIC_KEY
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.EncryptionUtils
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.showToast
import com.google.android.material.card.MaterialCardView

class HolderTextMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    private val blocUserMessage: MaterialCardView = view.findViewById(R.id.bloc_user_message)
    private val chatUserMessage: TextView = view.findViewById(R.id.chat_user_message)
    private val chatUserMessageTime: TextView = view.findViewById(R.id.chat_user_message_time)

    private val blocReceivedMessage: MaterialCardView = view.findViewById(R.id.bloc_received_message)
    private val chatReceivedMessage: TextView = view.findViewById(R.id.chat_received_message)
    private val chatReceivedMessageTime: TextView = view.findViewById(R.id.chat_received_message_time)

    override fun drawMessage(view: MessageView) {
        val text = decryptText(view)
        android.util.Log.d("DRAW", "from=${view.from}, text=${text.take(40)}...")

        if (view.from == CURRENT_UID) {
            blocUserMessage.visibility = View.VISIBLE
            blocReceivedMessage.visibility = View.GONE
            chatUserMessage.text = text
            chatUserMessageTime.text = view.timeStamp.asTime()
        } else {
            blocUserMessage.visibility = View.GONE
            blocReceivedMessage.visibility = View.VISIBLE
            chatReceivedMessage.text = text
            chatReceivedMessageTime.text = view.timeStamp.asTime()
        }
    }

    private fun decryptText(view: MessageView): String {
        if (view.text.isBlank()) return ""

        val chatPartnerId = view.from
        val originalText = view.text

        // === 1. Если уже расшифровано — сразу возвращаем ===
        if (view.decryptedText.isNotEmpty()) {
            return view.decryptedText
        }

        // === 2. Проверяем кэш публичного ключа ===
        val cachedPublicKey = ChatEncryptionManager.publicKeyCache[chatPartnerId]

        if (!cachedPublicKey.isNullOrEmpty()) {
            // Ключ есть — сразу расшифровываем
            return try {
                val chatKey = EncryptionUtils.deriveChatKey(cachedPublicKey)
                val decrypted = EncryptionUtils.decryptMessage(originalText, chatKey)
                view.decryptedText = decrypted  // кэшируем результат
                decrypted
            } catch (e: Exception) {
                android.util.Log.e("DECRYPT", "Decryption failed: ${e.message}")
                originalText.take(30) + "..."
            }
        }

        // === 3. Ключа нет в кэше — загружаем ОДИН РАЗ ===
        REF_DATABASE_ROOT.child(NODE_USERS)
            .child(chatPartnerId)
            .child(CHILD_PUBLIC_KEY)
            .get()
            .addOnSuccessListener { snapshot ->
                val publicKeyBase64 = snapshot.getValue(String::class.java)
                if (!publicKeyBase64.isNullOrEmpty()) {
                    ChatEncryptionManager.publicKeyCache[chatPartnerId] = publicKeyBase64

                    try {
                        val chatKey = EncryptionUtils.deriveChatKey(publicKeyBase64)
                        val decrypted = EncryptionUtils.decryptMessage(originalText, chatKey)

                        view.decryptedText = decrypted  // кэшируем

                        itemView.post {
                            if (originalText == view.text && view.decryptedText.isNotEmpty()) {
                                if (view.from == CURRENT_UID) {
                                    chatUserMessage.text = decrypted
                                } else {
                                    chatReceivedMessage.text = decrypted
                                }
                            }
                        }
                        android.util.Log.d("DECRYPT", "SUCCESS (loaded): ${decrypted.take(30)}...")
                    } catch (e: Exception) {
                        android.util.Log.e("DECRYPT", "FAILED: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("DECRYPT", "Failed to load public key: ${e.message}")
            }

        // Пока загружается — показываем заглушку
        return originalText.take(25) + "..."
    }

    // long click остаётся
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
                R.id.menu_copy -> { copyToClipboard(messageView.text); true }
                R.id.menu_delete -> { deleteMessage(messageView); true }
                R.id.menu_reply -> { replyToMessage(messageView); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = APP_ACTIVITY.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        showToast("Скопировано")
    }

    private fun deleteMessage(view: MessageView) = /* TODO */Unit
    private fun replyToMessage(view: MessageView) = /* TODO */Unit

    override fun onDetach() {
        chatUserMessage.setOnLongClickListener(null)
        chatReceivedMessage.setOnLongClickListener(null)
    }

    override fun onRecycled() {}
    override fun getMessageType(): String = "text"
}