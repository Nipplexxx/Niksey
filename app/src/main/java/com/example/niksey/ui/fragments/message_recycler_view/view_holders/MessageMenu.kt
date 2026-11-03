package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.example.niksey.R
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.showToast

/**
 * Общее меню сообщения (долгое нажатие): копировать, удалить, ответить.
 * Каждый тип сообщения задаёт только то, что копировать и как подписать ответ.
 */
internal fun showMessageMenu(
    anchor: View,
    message: MessageView,
    onCopy: () -> Unit,
    replyPreview: String
) {
    val popup = PopupMenu(anchor.context, anchor)
    popup.inflate(R.menu.message_context_menu)
    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.menu_copy -> onCopy()
            R.id.menu_delete -> findSingleChat()?.deleteMessage(message.id, message.from)
            R.id.menu_reply -> {
                val sender = if (message.from == CURRENT_UID) "Вы" else "Собеседник"
                findSingleChat()?.startReply(message.id, replyPreview, sender)
            }
            else -> return@setOnMenuItemClickListener false
        }
        true
    }
    popup.show()
}

/** Экран личного чата, если он открыт: сообщения удаляются и на него отвечают только там. */
internal fun findSingleChat(): SingleChatFragment? =
    APP_ACTIVITY.supportFragmentManager.fragments
        .firstOrNull { it is SingleChatFragment } as? SingleChatFragment

internal fun copyMessageText(label: String, text: String, toast: String) {
    val clipboard = APP_ACTIVITY.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    showToast(toast)
}
