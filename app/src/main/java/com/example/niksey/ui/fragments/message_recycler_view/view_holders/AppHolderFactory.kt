package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView

class AppHolderFactory {

    companion object {

        fun getHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return try {
                android.util.Log.d("AppHolderFactory", "Создаём холдер для viewType = $viewType")

                when (viewType) {

                    MessageView.MESSAGE_TEXT -> {
                        inflate(parent, R.layout.message_item_text) { HolderTextMessage(it) }
                    }

                    MessageView.MESSAGE_IMAGE -> {
                        inflate(parent, R.layout.message_item_image) { HolderImageMessage(it) }
                    }

                    MessageView.MESSAGE_VOICE -> {
                        inflate(parent, R.layout.message_item_voice) { HolderVoiceMessage(it) }
                    }

                    MessageView.MESSAGE_FILE -> {
                        inflate(parent, R.layout.message_item_file) { HolderFileMessage(it) }
                    }

                    MessageView.MESSAGE_VIDEO -> {
                        inflate(parent, R.layout.message_item_video) { HolderVideoMessage(it) }
                    }

                    else -> {
                        android.util.Log.w("AppHolderFactory", "Неизвестный viewType: $viewType")
                        EmptyHolder(View(parent.context))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppHolderFactory", "ОШИБКА создания холдера для viewType = $viewType", e)
                EmptyHolder(View(parent.context))
            }
        }

        private inline fun inflate(
            parent: ViewGroup,
            layoutRes: Int,
            holderCreator: (View) -> RecyclerView.ViewHolder
        ): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
            return holderCreator(view)
        }

        // Пустой холдер — чтобы чат никогда не падал
        private class EmptyHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}