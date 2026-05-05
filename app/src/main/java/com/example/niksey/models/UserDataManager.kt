package com.example.niksey.utillits

import android.content.Context
import android.content.SharedPreferences
import com.example.niksey.models.UserModel

object UserDataManager {

    private const val PREFS_NAME = "user_data_prefs"
    private const val KEY_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_FULLNAME = "fullname"
    private const val KEY_PHOTO_URL = "photo_url"
    private const val KEY_STATE = "state"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveUser(context: Context, user: UserModel) {
        val prefs = getPrefs(context)
        prefs.edit().apply {
            putString(KEY_ID, user.id)
            putString(KEY_USERNAME, user.username)
            putString(KEY_FULLNAME, user.fullname)
            putString(KEY_PHOTO_URL, user.photoUrl)
            putString(KEY_STATE, user.state as String?)
            apply()
        }
    }

    fun loadUser(context: Context): UserModel? {
        val prefs = getPrefs(context)
        val id = prefs.getString(KEY_ID, null) ?: return null

        return UserModel(
            id = id,
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            fullname = prefs.getString(KEY_FULLNAME, "") ?: "",
            photoUrl = prefs.getString(KEY_PHOTO_URL, "") ?: "",
            state = prefs.getString(KEY_STATE, "offline") ?: "offline"
        )
    }

    fun clearUser(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}