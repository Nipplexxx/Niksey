package com.example.niksey.ui.fragments.message_recycler_view.views

/**
 * Базовый интерфейс для всех типов сообщений в чате.
 * Реализуется в ViewTextMessage, ViewImageMessage, ViewVoiceMessage, ViewFileMessage.
 */
interface MessageView {

    val id: String
    val from: String
    val timeStamp: String
    val fileUrl: String
    val text: String

    /** Возвращает тип сообщения для RecyclerView (используется в getItemViewType) */
    fun getTypeView(): Int

    /** Удобный метод для проверки, является ли сообщение от текущего пользователя */
    fun isFromCurrentUser(): Boolean = from == com.example.niksey.database.CURRENT_UID

    companion object {
        const val MESSAGE_TEXT = 1
        const val MESSAGE_IMAGE = 0
        const val MESSAGE_VOICE = 2
        const val MESSAGE_FILE = 3
    }
}