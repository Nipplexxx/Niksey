package com.example.niksey.models

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class UserModel(
    var id: String = "",
    var username: String = "",
    var bio: String = "",
    var fullname: String = "",
    var state: Any = "",           // ← Any — принимает Long и String
    var phone: String = "",
    var photoUrl: String = "empty",
    var email: String = "",
    var password: String = "",
    var publicKey: String = ""
) {

    fun isOnline(): Boolean {
        val s = state
        return when (s) {
            is String -> s.lowercase() == "online" || s == "В сети"
            else -> false
        }
    }

    fun getDisplayName(): String {
        return fullname.ifBlank { username.ifBlank { phone.ifBlank { id } } }
    }

    fun getShortName(): String {
        return fullname.ifBlank { username }.take(20)
    }

    fun getStateText(): String {
        val s = state
        return when (s) {
            is String -> s
            is Long -> "был(а) недавно"
            else -> "offline"
        }
    }

    override fun toString(): String {
        return "UserModel(id='$id', username='$username', fullname='$fullname', state='$state')"
    }
}