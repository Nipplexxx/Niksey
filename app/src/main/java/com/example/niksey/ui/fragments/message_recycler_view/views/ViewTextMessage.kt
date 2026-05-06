package com.example.niksey.ui.fragments.message_recycler_view.views

data class ViewTextMessage(
    override val id: String,
    override val from: String,
    override val timeStamp: String,
    override val fileUrl: String,
    override val text: String = "",
    override val replyTo: String? = null,
    override val duration: String? = null
) : MessageView {
    override var decryptedText: String = ""

    override fun getTypeView(): Int = MessageView.MESSAGE_TEXT

    override fun isFromCurrentUser(): Boolean = from == com.example.niksey.utillits.CURRENT_UID

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageView
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String {
        return "ViewTextMessage(id='$id', from='$from', text='${text.take(30)}...')"
    }
}