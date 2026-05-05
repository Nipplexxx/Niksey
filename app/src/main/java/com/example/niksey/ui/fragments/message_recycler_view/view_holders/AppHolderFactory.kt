package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView

class AppHolderFactory {

    companion object {
        fun getHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
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

                else -> {
                    throw IllegalArgumentException("Unknown viewType: $viewType")
                }
            }
        }

        /** Вспомогательная функция для уменьшения повторения кода */
        private inline fun inflate(
            parent: ViewGroup,
            layoutRes: Int,
            holderCreator: (android.view.View) -> RecyclerView.ViewHolder
        ): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
            return holderCreator(view)
        }
    }
}