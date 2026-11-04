package com.example.niksey.models

import android.os.Parcel
import android.os.Parcelable

/**
 * Модель чата/контакта/сообщения.
 *
 * Parcelable — чтобы передавать в фрагменты через Bundle: при пересоздании фрагмента
 * системой (убийство процесса) данные сохраняются, а не теряются с полями конструктора.
 * timeStamp — Any (Long с сервера или пустая строка): читаем как Long, пишем строкой.
 */
data class CommonModel(
    val id: String = "",
    var username: String = "",
    var bio: String = "",
    var fullname: String = "",
    var state: String = "",
    var phone: String = "",
    var photoUrl: String = "empty",
    var text: String = "",
    var type: String = "",
    var from: String = "",
    var timeStamp: Any = "",
    var fileUrl: String = "empty",
    var lastMessage: String = "",
    var decryptedText: String = "",
    var choice: Boolean = false,
    var replyTo: String? = null,
    var duration: String? = null,
    /** Число непрочитанных сообщений (не сохраняется в Firebase — вычисляется при загрузке). */
    var unreadCount: Int = 0
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        username = parcel.readString() ?: "",
        bio = parcel.readString() ?: "",
        fullname = parcel.readString() ?: "",
        state = parcel.readString() ?: "",
        phone = parcel.readString() ?: "",
        photoUrl = parcel.readString() ?: "empty",
        text = parcel.readString() ?: "",
        type = parcel.readString() ?: "",
        from = parcel.readString() ?: "",
        timeStamp = parcel.readLong(),
        fileUrl = parcel.readString() ?: "empty",
        lastMessage = parcel.readString() ?: "",
        decryptedText = parcel.readString() ?: "",
        choice = parcel.readByte() != 0.toByte(),
        replyTo = parcel.readString(),
        duration = parcel.readString(),
        unreadCount = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(username)
        parcel.writeString(bio)
        parcel.writeString(fullname)
        parcel.writeString(state)
        parcel.writeString(phone)
        parcel.writeString(photoUrl)
        parcel.writeString(text)
        parcel.writeString(type)
        parcel.writeString(from)
        parcel.writeLong((timeStamp as? Long) ?: 0L)
        parcel.writeString(fileUrl)
        parcel.writeString(lastMessage)
        parcel.writeString(decryptedText)
        parcel.writeByte(if (choice) 1 else 0)
        parcel.writeString(replyTo)
        parcel.writeString(duration)
        parcel.writeInt(unreadCount)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CommonModel> {
        override fun createFromParcel(parcel: Parcel): CommonModel = CommonModel(parcel)
        override fun newArray(size: Int): Array<CommonModel?> = arrayOfNulls(size)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CommonModel
        return id == other.id
    }
    override fun hashCode(): Int {
        return id.hashCode()
    }
    override fun toString(): String {
        return "CommonModel(id='$id', username='$username', fullname='$fullname', type='$type')"
    }
}