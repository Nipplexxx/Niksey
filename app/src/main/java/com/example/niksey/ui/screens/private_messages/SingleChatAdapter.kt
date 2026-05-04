package com.example.niksey.ui.screens.private_messages

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.ui.fragments.message_recycler_view.view_holders.AppHolderFactory
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageHolder
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView

class SingleChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val mListMessagesCache = mutableListOf<MessageView>()
    private val mListHolders = mutableListOf<MessageHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return AppHolderFactory.getHolder(parent, viewType)
    }

    override fun getItemViewType(position: Int): Int {
        return mListMessagesCache[position].getTypeView()
    }

    override fun getItemCount(): Int = mListMessagesCache.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as MessageHolder).drawMessage(mListMessagesCache[position])
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val messageHolder = holder as MessageHolder
        val position = holder.adapterPosition
        if (position != RecyclerView.NO_POSITION) {
            messageHolder.onAttach(mListMessagesCache[position])
            mListHolders.add(messageHolder)
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val messageHolder = holder as MessageHolder
        messageHolder.onDetach()
        mListHolders.remove(messageHolder)
    }

    fun addItemToBottom(item: MessageView, onSuccess: () -> Unit) {
        if (!mListMessagesCache.contains(item)) {
            mListMessagesCache.add(item)
            notifyItemInserted(mListMessagesCache.lastIndex)
            onSuccess()
        }
    }

    fun addItemToTop(item: MessageView, onSuccess: () -> Unit) {
        if (!mListMessagesCache.contains(item)) {
            mListMessagesCache.add(0, item)
            notifyItemInserted(0)
            onSuccess()
        }
    }

    fun onDestroy() {
        mListHolders.forEach { it.onDetach() }
        mListHolders.clear()
    }
}