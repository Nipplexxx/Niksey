package com.example.niksey.models

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
    var choice: Boolean = false
) {

    // equals и hashCode только по id
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CommonModel
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    // Полезный toString для отладки
    override fun toString(): String {
        return "CommonModel(id='$id', username='$username', fullname='$fullname', type='$type')"
    }
}