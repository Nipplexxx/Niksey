package com.example.niksey.ui.fragments.message_recycler_view.views

data class ViewVoiceMessage(
    override val id: String,
    override val from: String,
    override val timeStamp: String,
    override val fileUrl: String,
    override val text: String = ""
) : MessageView {

    override fun getTypeView(): Int = MessageView.MESSAGE_VOICE

    override fun isFromCurrentUser(): Boolean = from == com.example.niksey.database.CURRENT_UID

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageView
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String {
        return "ViewVoiceMessage(id='$id', from='$from')"
    }
}