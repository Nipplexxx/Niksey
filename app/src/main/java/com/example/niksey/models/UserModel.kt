package com.example.niksey.models

/**
 * Модель пользователя.
 * Содержит основные данные профиля.
*/

data class UserModel(
    val id: String = "",
    var username: String = "",
    var bio: String = "",
    var fullname: String = "",
    var state: String = "",
    var phone: String = "",
    var photoUrl: String = "empty",
    var email: String = "",
    var password: String = ""
) {

    /** Пользователь онлайн? */
    fun isOnline(): Boolean {
        return state.lowercase() == "online" || state == "В сети"
    }

    /** Отображаемое имя (fullname или username) */
    fun getDisplayName(): String {
        return fullname.ifBlank { username.ifBlank { phone.ifBlank { id } } }
    }

    /** Короткое имя для списка */
    fun getShortName(): String {
        return fullname.ifBlank { username }.take(20)
    }

    override fun toString(): String {
        return "UserModel(id='$id', username='$username', fullname='$fullname', state='$state')"
    }
}