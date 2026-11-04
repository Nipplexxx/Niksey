package com.example.niksey.ui.fragments.message_recycler_view.views

import com.example.niksey.models.CommonModel
import com.example.niksey.utillits.*

/**
 * Превращает запись из базы (CommonModel) в модель для отображения (MessageView).
 *
 * ВАЖНО: message.text — это зашифрованные метаданные вложения (ключ файла + имя,
 * см. MediaRepository.encodeMeta). Без них медиа нельзя расшифровать, поэтому text
 * передаётся ВСЕМ медиа-типам (image/voice/video/file), а не только текстовым сообщениям.
 * Тип записи (message.type) задаёт подкласс; неизвестный тип показывается как текст —
 * чат не ломается при новых типах сообщений от обновлённой версии приложения.
 */
class AppViewFactory {

    companion object {

        fun getView(message: CommonModel): MessageView {
            return when (message.type) {

                TYPE_MESSAGE_TEXT -> ViewTextMessage(
                    id = message.id,
                    from = message.from,
                    timeStamp = message.timeStamp.toString(),
                    fileUrl = message.fileUrl,
                    text = message.text,
                    replyTo = message.replyTo
                )

                TYPE_MESSAGE_IMAGE -> ViewImageMessage(
                    id = message.id,
                    from = message.from,
                    timeStamp = message.timeStamp.toString(),
                    fileUrl = message.fileUrl,
                    text = message.text,
                    replyTo = message.replyTo
                )

                TYPE_MESSAGE_VOICE -> ViewVoiceMessage(
                    id = message.id,
                    from = message.from,
                    timeStamp = message.timeStamp.toString(),
                    fileUrl = message.fileUrl,
                    text = message.text,
                    replyTo = message.replyTo
                )

                TYPE_MESSAGE_FILE -> ViewFileMessage(
                    id = message.id,
                    from = message.from,
                    timeStamp = message.timeStamp.toString(),
                    fileUrl = message.fileUrl,
                    text = message.text,
                    replyTo = message.replyTo
                )

                TYPE_MESSAGE_VIDEO -> ViewVideoMessage(
                    id = message.id,
                    from = message.from,
                    timeStamp = message.timeStamp.toString(),
                    fileUrl = message.fileUrl,
                    text = message.text,
                    replyTo = message.replyTo,
                    duration = message.duration ?: "0:15"
                )

                else -> {
                    // Неизвестный тип — показываем как текст
                    ViewTextMessage(
                        id = message.id,
                        from = message.from,
                        timeStamp = message.timeStamp.toString(),
                        fileUrl = message.fileUrl,
                        text = message.text,
                        replyTo = message.replyTo
                    )
                }
            }
        }
    }
}
