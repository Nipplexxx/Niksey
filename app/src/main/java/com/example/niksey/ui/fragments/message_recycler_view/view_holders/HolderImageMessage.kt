@file:Suppress("UNCHECKED_CAST", "DEPRECATION")
package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.ui.screens.fullscreen.ImagePreviewBottomSheet
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.showToast
import com.google.android.material.card.MaterialCardView

class HolderImageMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    private val blocUserImage: MaterialCardView = view.findViewById(R.id.bloc_user_image)
    private val chatUserImage: ImageView = view.findViewById(R.id.chat_user_image)
    private val chatUserImageTime: TextView = view.findViewById(R.id.chat_user_image_time)
    private val userReplyPreview: TextView = view.findViewById(R.id.user_reply_preview)

    private val blocReceivedImage: MaterialCardView = view.findViewById(R.id.bloc_received_image)
    private val chatReceivedImage: ImageView = view.findViewById(R.id.chat_received_image)
    private val chatReceivedImageTime: TextView = view.findViewById(R.id.chat_received_image_time)
    private val receivedReplyPreview: TextView = view.findViewById(R.id.received_reply_preview)

    override fun drawMessage(view: MessageView) {
        if (view.from == CURRENT_UID) {
            showUserImage(view)
        } else {
            showReceivedImage(view)
        }
    }

    private fun showUserImage(view: MessageView) {
        blocUserImage.visibility = View.VISIBLE
        blocReceivedImage.visibility = View.GONE

        chatUserImage.downloadAndSetImage(view.fileUrl)
        chatUserImageTime.text = view.timeStamp.asTime()

        showReplyPreview(view, userReplyPreview)

        val clickListener = View.OnClickListener {
            if (view.fileUrl.isNotBlank()) {
                ImagePreviewBottomSheet.newInstance(view.fileUrl)
                    .show(APP_ACTIVITY.supportFragmentManager, "image_preview")
            }
        }
        chatUserImage.setOnClickListener(clickListener)
        blocUserImage.setOnClickListener(clickListener)
    }

    private fun showReceivedImage(view: MessageView) {
        blocUserImage.visibility = View.GONE
        blocReceivedImage.visibility = View.VISIBLE

        chatReceivedImage.downloadAndSetImage(view.fileUrl)
        chatReceivedImageTime.text = view.timeStamp.asTime()

        showReplyPreview(view, receivedReplyPreview)

        val clickListener = View.OnClickListener {
            if (view.fileUrl.isNotBlank()) {
                ImagePreviewBottomSheet.newInstance(view.fileUrl)
                    .show(APP_ACTIVITY.supportFragmentManager, "image_preview")
            }
        }
        chatReceivedImage.setOnClickListener(clickListener)
        blocReceivedImage.setOnClickListener(clickListener)
    }

    private fun showReplyPreview(messageView: MessageView, previewView: TextView) {
        if (messageView.replyTo.isNullOrEmpty()) {
            previewView.visibility = View.GONE
            return
        }

        previewView.visibility = View.VISIBLE
        previewView.text = "↩️ Ответ на фото"

        previewView.setOnClickListener {
            getSingleChatFragment()?.scrollToMessage(messageView.replyTo!!)
        }
    }

    override fun onAttach(view: MessageView) {
        val longClickListener = View.OnLongClickListener {
            showMessageOptions(view, it)
            true
        }
        chatUserImage.setOnLongClickListener(longClickListener)
        chatReceivedImage.setOnLongClickListener(longClickListener)
    }

    private fun showMessageOptions(messageView: MessageView, anchor: View) {
        val popup = PopupMenu(itemView.context, anchor)
        popup.inflate(R.menu.message_context_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_copy -> {
                    copyImageUrl(messageView.fileUrl)
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
        fragment.startReply(view.id, "[Фото]", senderName)
    }

    private fun copyImageUrl(url: String) {
        if (url.isBlank()) {
            showToast("Нет ссылки на фото")
            return
        }
        val clipboard = APP_ACTIVITY.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("image_url", url))
        showToast("Ссылка на фото скопирована")
    }

    private fun getSingleChatFragment(): SingleChatFragment? {
        return APP_ACTIVITY.supportFragmentManager.fragments
            .firstOrNull { it is SingleChatFragment } as? SingleChatFragment
    }

    override fun onDetach() {
        chatUserImage.setOnClickListener(null)
        chatReceivedImage.setOnClickListener(null)
        chatUserImage.setOnLongClickListener(null)
        chatReceivedImage.setOnLongClickListener(null)
    }

    override fun onRecycled() {}
    override fun getMessageType(): String = "image"
}