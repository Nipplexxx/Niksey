package com.example.niksey.ui.objects

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.niksey.R

/**
 * Меню чата в шапке. Панель заголовка скрыта на экранах чата, поэтому меню открывается
 * всплывающим списком от кнопки «⋮». Пункты берутся из того же меню, что и раньше.
 */
@Suppress("DEPRECATION")
fun Fragment.showChatMenu(anchor: View, menuRes: Int) {
    val popup = PopupMenu(requireContext(), anchor)
    popup.menuInflater.inflate(menuRes, popup.menu)

    // Исходное меню — один пункт-«контейнер» с подменю; разворачиваем его в плоский список
    val nested = popup.menu.getItem(0).subMenu ?: return
    val items = (0 until nested.size()).map { nested.getItem(it) }
        .map { Triple(it.itemId, it.title, it.icon) }

    popup.menu.clear()
    items.forEach { (id, title, icon) ->
        popup.menu.add(0, id, 0, title).setIcon(icon)
    }
    popup.setOnMenuItemClickListener { onOptionsItemSelected(it) }
    popup.show()
}

/** Точка статуса в шапке: teal — онлайн, приглушённый серый — офлайн. */
fun setStatusDotColor(dot: View, online: Boolean) {
    (dot.background as? GradientDrawable)?.setColor(
        ContextCompat.getColor(dot.context, if (online) R.color.accent_cyan else R.color.text_hint)
    )
}
