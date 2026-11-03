package com.example.niksey.utillits

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

/**
 * Слушатель одиночного чтения Firebase.
 *
 * [onError] нужен экранам, где от результата зависит индикатор загрузки: без него
 * отменённое чтение (нет прав, нет сети) никогда не сообщит, что загрузка закончилась.
 * Использование: `AppValueEventListener(::onError) { snapshot -> ... }`.
 */
class AppValueEventListener(
    val onError: ((DatabaseError) -> Unit)? = null,
    val onSuccess: (DataSnapshot) -> Unit
) : ValueEventListener {

    override fun onCancelled(p0: DatabaseError) {
        onError?.invoke(p0)
    }

    override fun onDataChange(p0: DataSnapshot) {
        onSuccess(p0)
    }

}
