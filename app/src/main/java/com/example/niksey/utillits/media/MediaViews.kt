package com.example.niksey.utillits.media

import android.widget.ImageView
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.ChatEncryptionManager
import com.squareup.picasso.Picasso
import java.io.File

/** Удобные обёртки для холдеров: получить локальный файл сообщения и показать его. */
object MediaViews {

    /**
     * Передаёт локальный расшифрованный файл вложения.
     * Ничего не делает, если беседа не открыта (currentConversationId == null) —
     * в этом случае экран перерисуется, когда беседа откроется.
     * Ошибка KeyPendingException глушится: ключ беседы ещё загружается,
     * после готовности ключа экран перерисуется и медиа подхватится.
     */
    fun withFile(view: MessageView, onFile: (File) -> Unit, onError: (Throwable) -> Unit = {}) {
        val conversationId = ChatEncryptionManager.currentConversationId ?: return
        MediaRepository.resolve(view, conversationId) { result ->
            result
                .onSuccess(onFile)
                .onFailure { error ->
                    // Ключ беседы ещё загружается — это не ошибка, повторная отрисовка придёт сама
                    if (error !is MediaRepository.KeyPendingException) onError(error)
                }
        }
    }

    /**
     * Показывает картинку из кэша. [imageView.tag] защищает от «чужой» картинки,
     * если строка переиспользована при прокрутке.
     */
    fun showImage(view: MessageView, imageView: ImageView, placeholderRes: Int) {
        imageView.tag = view.id
        imageView.setImageResource(placeholderRes)
        withFile(view, { file ->
            if (imageView.tag == view.id) {
                Picasso.get().load(file).fit().centerCrop().into(imageView)
            }
        })
    }
}
