package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.CURRENT_UID
import com.example.niksey.database.REF_DATABASE_ROOT
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageHolder
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.APP_ACTIVITY
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
        if (view.from == CURRENT_UID) {
            showUserMessage(view)
        } else {
            showReceivedMessage(view)
        }
    }

    private fun showUserMessage(view: MessageView) {
        blocUserMessage.visibility = View.VISIBLE
        blocReceivedMessage.visibility = View.GONE
        chatUserMessage.text = view.text
        chatUserMessageTime.text = view.timeStamp.asTime()
    }

    private fun showReceivedMessage(view: MessageView) {
        blocUserMessage.visibility = View.GONE
        blocReceivedMessage.visibility = View.VISIBLE
        chatReceivedMessage.text = view.text
        chatReceivedMessageTime.text = view.timeStamp.asTime()
    }

    override fun onAttach(view: MessageView) {
        chatUserMessage.setOnLongClickListener {
            showMessageOptions(view, chatUserMessage)
            true
        }

        chatReceivedMessage.setOnLongClickListener {
            showMessageOptions(view, chatReceivedMessage)
            true
        }
    }

    private fun showMessageOptions(messageView: MessageView, anchorView: View) {
        val popup = PopupMenu(itemView.context, anchorView)
        popup.inflate(R.menu.message_context_menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_copy -> {
                    copyToClipboard(messageView.text)
                    true
                }
                R.id.menu_delete -> {
                    deleteMessage(messageView)
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

    private fun copyToClipboard(text: String) {
        val clipboard = APP_ACTIVITY.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("message", text)
        clipboard.setPrimaryClip(clip)
        showToast(APP_ACTIVITY.getString(R.string.message_copied))
    }

    private fun deleteMessage(view: MessageView) {
        val path = if (view.from == CURRENT_UID) {
            "private_messages/$CURRENT_UID/${view.id}"
        } else {
            "private_messages/${view.from}/$CURRENT_UID/${view.id}"
        }

        REF_DATABASE_ROOT.child(path).removeValue()
            .addOnSuccessListener {
                showToast(APP_ACTIVITY.getString(R.string.message_deleted))
            }
            .addOnFailureListener {
                showToast(APP_ACTIVITY.getString(R.string.error_deleting_message, it.message))
            }
    }

    private fun replyToMessage(view: MessageView) {
        val inputField = APP_ACTIVITY.findViewById<android.widget.EditText>(R.id.chat_input_message)
        if (inputField != null) {
            inputField.setText("${view.text}")
            inputField.setSelection(inputField.text.length)
            inputField.requestFocus()
            showToast(APP_ACTIVITY.getString(R.string.reply_mode_activated))
        } else {
            showToast(APP_ACTIVITY.getString(R.string.reply_mode_failed))
        }
    }

    override fun onDetach() {
        chatUserMessage.setOnLongClickListener(null)
        chatReceivedMessage.setOnLongClickListener(null)
    }

    override fun onRecycled() {
        // Очистка при переиспользовании
    }

    override fun getMessageType(): String = "text"
}