package com.example.niksey.ui.fragments.message_recycler_view.views

import com.example.niksey.models.CommonModel
import com.example.niksey.utillits.*

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
                    replyTo = message.replyTo
                )

                TYPE_MESSAGE_VOICE -> ViewVoiceMessage(
                    id = message.id,
                    from = message.from,
                    timeStamp = message.timeStamp.toString(),
                    fileUrl = message.fileUrl,
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