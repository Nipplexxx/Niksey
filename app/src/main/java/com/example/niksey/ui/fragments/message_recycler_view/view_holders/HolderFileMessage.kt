package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.EncryptionUtils
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.media.MediaRepository
import com.example.niksey.utillits.media.MediaViews
import com.example.niksey.utillits.showToast
import com.example.niksey.utillits.signal.SecureMessaging
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
        // Имя лежит в зашифрованных метаданных. Если ещё не расшифровано — запускаем фоновую расшифровку;
        // по её окончании экран перерисуется (SecureMessaging.onDecryptedUpdate)
        MediaRepository.cachedName(view)?.let { return it }
        ChatEncryptionManager.currentConversationId?.let { SecureMessaging.prefetch(view.id, view.from, it, view.text) }
        return "Файл"
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

    /**
     * Файл сначала расшифровывается в кэш (см. MediaRepository), затем копируется в «Загрузки»
     * под исходным именем — это действие пользователя, поэтому копия становится видна системе.
     *
     * Android 10+ не даёт писать в публичные Загрузки напрямую: на Q+ файл попадает туда
     * через MediaStore (запись делает система), на старых версиях — обычной записью.
     */
    private fun downloadFile(messageView: MessageView) {
        val isUser = messageView.from == CURRENT_UID
        showProgress(isUser)

        MediaViews.withFile(messageView, { cached ->
            try {
                val uri = saveToDownloads(cached, fileDisplayName(messageView))
                hideProgress(isUser)
                if (uri != null) {
                    showToast("Файл сохранён в Загрузки")
                    openDownloadedFile(uri)
                } else {
                    showToast("Не удалось сохранить файл")
                }
            } catch (e: Exception) {
                hideProgress(isUser)
                showToast("Ошибка сохранения: ${e.message}")
            }
        }, { error ->
            // Ключ беседы ещё загружается — не пугаем пользователя ошибкой
            if (error !is MediaRepository.KeyPendingException) {
                hideProgress(isUser)
                showToast("Ошибка скачивания: ${error.message}")
            }
        })
    }

    /** Копирует файл в Загрузки; возвращает uri, по которому файл можно открыть. */
    private fun saveToDownloads(source: File, displayName: String): android.net.Uri? {
        val name = displayName.ifBlank { "file_${System.currentTimeMillis()}" }
        val mime = mimeOf(name)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = APP_ACTIVITY.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: throw java.io.IOException("Нет потока записи")
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val target = File(downloadsDir, name)
            source.copyTo(target, overwrite = true)
            FileProvider.getUriForFile(APP_ACTIVITY, "${APP_ACTIVITY.packageName}.fileprovider", target)
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4", "mov" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun fileDisplayName(messageView: MessageView): String =
        MediaRepository.cachedName(messageView)?.ifEmpty { null } ?: "file"

    private fun openDownloadedFile(uri: android.net.Uri) {
        try {
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
        showMessageMenu(
            anchor = anchorView,
            message = messageView,
            onCopy = { copyToClipboard(getDisplayFileName(MediaRepository.cachedName(messageView))) },
            replyPreview = "[Файл] ${getDisplayFileName(MediaRepository.cachedName(messageView))}"
        )
    }

    private fun copyToClipboard(text: String) {
        val clipboard = APP_ACTIVITY.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("file", text))
        showToast("Имя файла скопировано")
    }

    override fun onDetach() {
        chatUserBtnDownload.setOnClickListener(null)
        chatReceivedBtnDownload.setOnClickListener(null)
        chatUserBtnDownload.setOnLongClickListener(null)
        chatReceivedBtnDownload.setOnLongClickListener(null)
    }

    override fun onRecycled() {}
    override fun getMessageType(): String = "file"
}