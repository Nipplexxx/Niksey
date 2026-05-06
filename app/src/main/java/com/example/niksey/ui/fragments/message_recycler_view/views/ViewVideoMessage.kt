package com.example.niksey.ui.fragments.message_recycler_view.views

import com.example.niksey.utillits.CURRENT_UID

data class ViewVideoMessage(
    override val id: String,
    override val from: String,
    override val timeStamp: String,
    override val fileUrl: String,
    override val text: String = "",
    override val replyTo: String? = null,
    override val duration: String? = null
) : MessageView {

    override var decryptedText: String = ""

    override fun getTypeView(): Int = MessageView.MESSAGE_VIDEO

    override fun isFromCurrentUser(): Boolean = from == CURRENT_UID

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageView
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String {
        return "ViewVideoMessage(id='$id', from='$from', duration='${duration ?: "0:00"}')"
    }
}