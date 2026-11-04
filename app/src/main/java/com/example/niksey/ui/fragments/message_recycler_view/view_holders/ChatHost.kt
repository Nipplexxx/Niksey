package com.example.niksey.ui.fragments.message_recycler_view.view_holders

/**
 * Экран чата (личный или групповой), открытый поверх остальных.
 *
 * Холдеры сообщений работают с любым чатом через этот интерфейс: удалить сообщение,
 * ответить на него или прокрутить к нему — раньше работало только в личном чате,
 * потому что холдеры искали конкретный класс фрагмента.
 */
interface ChatHost {
    fun deleteMessage(messageId: String, fromUserId: String)
    fun scrollToMessage(messageId: String)
    fun startReply(messageId: String, messageText: String, fromUser: String)
}
