package com.example.niksey.ui.fragments.message_recycler_view.views

import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView

    /**
        * Интерфейс для всех ViewHolder'ов сообщений в чате.
    */
interface MessageHolder {

    fun drawMessage(view: MessageView)

    fun onAttach(view: MessageView) {
        // Пустая реализация по умолчанию
    }

    fun onDetach() {
        // Пустая реализация по умолчанию
    }

    fun onRecycled() {
        // Пустая реализация по умолчанию
    }

    fun onLongClick(view: MessageView): Boolean {
        return false
    }

    fun getMessageType(): String = "unknown"
}