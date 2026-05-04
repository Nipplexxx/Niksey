package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.CURRENT_UID
import com.example.niksey.database.getFileFromStorage
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageHolder
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.showToast
import com.google.android.material.card.MaterialCardView
import java.io.File

class HolderFileMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    // === ПОЛУЧЕННЫЙ ФАЙЛ ===
    private val blocReceivedFileMessage: MaterialCardView = view.findViewById(R.id.bloc_received_file_message)
    private val chatReceivedFileIcon: ImageView = view.findViewById(R.id.chat_received_file_icon)
    private val chatReceivedFilename: TextView = view.findViewById(R.id.chat_received_filename)
    private val chatReceivedFileInfo: TextView = view.findViewById(R.id.chat_received_file_info)
    private val chatReceivedBtnDownload: ImageView = view.findViewById(R.id.chat_received_btn_download)
    private val chatReceivedProgressBar: android.widget.ProgressBar = view.findViewById(R.id.chat_received_progress_bar)
    private val chatReceivedFileMessageTime: TextView = view.findViewById(R.id.chat_received_file_message_time)

    // === ОТПРАВЛЕННЫЙ ФАЙЛ ===
    private val blocUserFileMessage: MaterialCardView = view.findViewById(R.id.bloc_user_file_message)
    private val chatUserFileIcon: ImageView = view.findViewById(R.id.chat_user_file_icon)
    private val chatUserFilename: TextView = view.findViewById(R.id.chat_user_filename)
    private val chatUserFileInfo: TextView = view.findViewById(R.id.chat_user_file_info)
    private val chatUserBtnDownload: ImageView = view.findViewById(R.id.chat_user_btn_download)
    private val chatUserProgressBar: android.widget.ProgressBar = view.findViewById(R.id.chat_user_progress_bar)
    private val chatUserFileMessageTime: TextView = view.findViewById(R.id.chat_user_file_message_time)

    override fun drawMessage(view: MessageView) {
        if (view.from == CURRENT_UID) showUserFile(view) else showReceivedFile(view)
    }

    private fun showUserFile(view: MessageView) {
        blocUserFileMessage.visibility = View.VISIBLE
        blocReceivedFileMessage.visibility = View.GONE

        val decryptedFileName = decryptFileName(view)
        chatUserFilename.text = getDisplayFileName(decryptedFileName)
        chatUserFileInfo.text = getFileInfo(decryptedFileName)
        chatUserFileIcon.setImageResource(getFileIcon(decryptedFileName))
        chatUserFileMessageTime.text = view.timeStamp.asTime()
        chatUserBtnDownload.setImageResource(R.drawable.ic_download_white)
        chatUserProgressBar.visibility = View.INVISIBLE
    }

    private fun showReceivedFile(view: MessageView) {
        blocUserFileMessage.visibility = View.GONE
        blocReceivedFileMessage.visibility = View.VISIBLE

        val decryptedFileName = decryptFileName(view)
        chatReceivedFilename.text = getDisplayFileName(decryptedFileName)
        chatReceivedFileInfo.text = getFileInfo(decryptedFileName)
        chatReceivedFileIcon.setImageResource(getFileIcon(decryptedFileName))
        chatReceivedFileMessageTime.text = view.timeStamp.asTime()
        chatReceivedBtnDownload.setImageResource(R.drawable.ic_download)
        chatReceivedProgressBar.visibility = View.INVISIBLE
    }

    // ==================== НОВАЯ ВЕРСИЯ РАСШИФРОВКИ ====================
    private fun decryptFileName(view: MessageView): String {
        return try {
            val chatKey = ChatEncryptionManager.getChatKey(view.from)

            if (chatKey != null) {
                ChatEncryptionManager.decryptMessage(view.text, chatKey)
            } else {
                // Если ключа нет — показываем оригинальное имя файла
                view.text.ifEmpty { "Файл" }
            }
        } catch (e: Exception) {
            view.text.ifEmpty { "Файл" }
        }
    }
    // ===============================================================

    private fun getDisplayFileName(name: String?): String {
        if (name.isNullOrEmpty()) return "Файл"
        return if (name.length > 32) name.take(29) + "..." else name
    }

    private fun getFileInfo(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.uppercase() ?: ""
        return if (ext.isNotEmpty()) "• $ext" else ""
    }

    private fun getFileIcon(name: String?): Int {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: ""
        return when (ext) {
            "pdf" -> R.drawable.ic_file_pdf
            "doc", "docx" -> R.drawable.ic_file_word
            "xls", "xlsx" -> R.drawable.ic_file_excel
            "ppt", "pptx" -> R.drawable.ic_file_powerpoint
            "jpg", "jpeg", "png", "gif", "webp" -> R.drawable.ic_file_image
            "mp4", "mov", "avi" -> R.drawable.ic_file_video
            "mp3", "wav", "ogg" -> R.drawable.ic_file_audio
            "zip", "rar", "7z" -> R.drawable.ic_file_archive
            else -> R.drawable.ic_file_generic
        }
    }

    override fun onAttach(view: MessageView) {
        val clickListener = View.OnClickListener { downloadFile(view) }
        val longClickListener = View.OnLongClickListener {
            showMessageOptions(view, it)
            true
        }

        chatUserBtnDownload.setOnClickListener(clickListener)
        chatReceivedBtnDownload.setOnClickListener(clickListener)
        chatUserBtnDownload.setOnLongClickListener(longClickListener)
        chatReceivedBtnDownload.setOnLongClickListener(longClickListener)
    }

    private fun downloadFile(messageView: MessageView) {
        val fileUrl = messageView.fileUrl
        if (fileUrl.isEmpty()) {
            showToast("Файл недоступен")
            return
        }

        val isUser = messageView.from == CURRENT_UID
        showProgress(isUser)

        val fileName = messageView.text ?: "file_${System.currentTimeMillis()}"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)

        try {
            file.createNewFile()
            getFileFromStorage(file, fileUrl) {
                hideProgress(isUser)
                showToast("Файл сохранён в Загрузки")
                openDownloadedFile(file)
            }
        } catch (e: Exception) {
            hideProgress(isUser)
            showToast("Ошибка скачивания: ${e.message}")
        }
    }

    private fun openDownloadedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                APP_ACTIVITY,
                "${APP_ACTIVITY.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            APP_ACTIVITY.startActivity(intent)
        } catch (e: Exception) {
            showToast("Не удалось открыть файл")
        }
    }

    private fun showProgress(isUser: Boolean) {
        if (isUser) {
            chatUserBtnDownload.visibility = View.INVISIBLE
            chatUserProgressBar.visibility = View.VISIBLE
        } else {
            chatReceivedBtnDownload.visibility = View.INVISIBLE
            chatReceivedProgressBar.visibility = View.VISIBLE
        }
    }

    private fun hideProgress(isUser: Boolean) {
        if (isUser) {
            chatUserBtnDownload.visibility = View.VISIBLE
            chatUserProgressBar.visibility = View.INVISIBLE
        } else {
            chatReceivedBtnDownload.visibility = View.VISIBLE
            chatReceivedProgressBar.visibility = View.INVISIBLE
        }
    }

    private fun showMessageOptions(messageView: MessageView, anchorView: View) {
        val popup = PopupMenu(itemView.context, anchorView)
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
        clipboard.setPrimaryClip(ClipData.newPlainText("file", text))
        showToast("Имя файла скопировано")
    }

    private fun deleteMessage(view: MessageView) { /* TODO */ }
    private fun replyToMessage(view: MessageView) { /* TODO */ }

    override fun onDetach() {
        chatUserBtnDownload.setOnClickListener(null)
        chatReceivedBtnDownload.setOnClickListener(null)
        chatUserBtnDownload.setOnLongClickListener(null)
        chatReceivedBtnDownload.setOnLongClickListener(null)
    }

    override fun onRecycled() {}
    override fun getMessageType(): String = "file"
}