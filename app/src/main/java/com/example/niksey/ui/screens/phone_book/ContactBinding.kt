package com.example.niksey.ui.screens.phone_book

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.models.UserModel
import com.example.niksey.utillits.AppValueEventListener
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.downloadAndSetImage
import de.hdodenhof.circleimageview.CircleImageView

/**
 * Общее отображение строки контакта.
 *
 * ContactsFragment и AddContactsFragment показывают контакты одинаково (фото, имя, статус),
 * но берут данные из разных запросов Firebase — раньше обе копии onBindViewHolder
 * дублировали друг друга. Здесь — одна реализация привязки.
 */
object ContactBinding {

    class ContactHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.contact_fullname)
        val status: TextView = view.findViewById(R.id.contact_status)
        val photo: CircleImageView = view.findViewById(R.id.contact_photo)
    }

    fun createHolder(view: View): ContactHolder = ContactHolder(view)

    /**
     * Заполняет строку данными пользователя.
     * Слушатель одноразовый (SingleValueEvent): статус обновится при пересоздании списка.
     */
    fun bind(holder: ContactHolder, userId: String) {
        REF_DATABASE_ROOT.child(NODE_USERS).child(userId)
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                val user = snapshot.getValue(UserModel::class.java) ?: return@AppValueEventListener
                holder.name.text = user.fullname.ifEmpty { user.username }
                holder.status.text = statusText(user)
                holder.photo.downloadAndSetImage(user.photoUrl)
            })
    }

    /** Статус с «печатает…» — как в Telegram. */
    private fun statusText(user: UserModel): String = when (user.state) {
        "online" -> "онлайн"
        "prints" -> "печатает…"
        "recording" -> "записывает голосовое..."
        else -> user.getStateText()
    }
}
