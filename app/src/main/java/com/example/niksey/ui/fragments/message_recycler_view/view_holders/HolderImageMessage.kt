package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.CURRENT_UID
import com.example.niksey.database.REF_DATABASE_ROOT
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageHolder
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.ui.screens.fullscreen.ImagePreviewBottomSheet
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.showToast
import com.google.android.material.card.MaterialCardView

class HolderImageMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    private val blocUserImage: MaterialCardView = view.findViewById(R.id.bloc_user_image)
    private val chatUserImage: ImageView = view.findViewById(R.id.chat_user_image)
    private val chatUserImageTime: TextView = view.findViewById(R.id.chat_user_image_time)

    private val blocReceivedImage: MaterialCardView = view.findViewById(R.id.bloc_received_image)
    private val chatReceivedImage: ImageView = view.findViewById(R.id.chat_received_image)
    private val chatReceivedImageTime: TextView = view.findViewById(R.id.chat_received_image_time)

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
        chatUserImageTime.text = view.timeStamp.asTime()
        chatUserImage.downloadAndSetImage(view.fileUrl)
    }

    private fun showReceivedImage(view: MessageView) {
        blocUserImage.visibility = View.GONE
        blocReceivedImage.visibility = View.VISIBLE
        chatReceivedImageTime.text = view.timeStamp.asTime()
        chatReceivedImage.downloadAndSetImage(view.fileUrl)
    }

    override fun onAttach(view: MessageView) {
        val clickListener = View.OnClickListener {
            val bottomSheet = ImagePreviewBottomSheet.newInstance(view.fileUrl)
            bottomSheet.show(
                (itemView.context as androidx.fragment.app.FragmentActivity).supportFragmentManager,
                "image_preview"
            )
        }

        chatUserImage.setOnClickListener(clickListener)
        chatReceivedImage.setOnClickListener(clickListener)

        // Долгий клик
        val longClickListener = View.OnLongClickListener {
            showMessageOptions(view, it)
            true
        }
        chatUserImage.setOnLongClickListener(longClickListener)
        chatReceivedImage.setOnLongClickListener(longClickListener)
    }

    private fun showMessageOptions(messageView: MessageView, anchorView: View) {
        val popup = PopupMenu(itemView.context, anchorView)
        popup.inflate(R.menu.message_context_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_copy -> { /* TODO: скопировать ссылку на фото */ true }
                R.id.menu_delete -> { deleteMessage(messageView); true }
                R.id.menu_reply -> { replyToMessage(messageView); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun deleteMessage(view: MessageView) {
        val path = if (view.from == CURRENT_UID)
            "private_messages/$CURRENT_UID/${view.id}"
        else
            "private_messages/${view.from}/$CURRENT_UID/${view.id}"

        REF_DATABASE_ROOT.child(path).removeValue()
            .addOnSuccessListener { showToast(APP_ACTIVITY.getString(R.string.message_deleted)) }
            .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_deleting_message, it.message)) }
    }

    private fun replyToMessage(view: MessageView) {
        val inputField = APP_ACTIVITY.findViewById<android.widget.EditText>(R.id.chat_input_message)
        inputField?.apply {
            setText("↩️ [Image]")
            setSelection(text.length)
            requestFocus()
        } ?: showToast(APP_ACTIVITY.getString(R.string.reply_mode_failed))
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