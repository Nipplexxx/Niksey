package com.example.niksey.ui.screens.groups_messages

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.ui.fragments.message_recycler_view.view_holders.AppHolderFactory
import com.example.niksey.ui.fragments.message_recycler_view.view_holders.MessageHolder
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView

@Suppress("DEPRECATION")
class GroupChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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

    fun onDestroy() {
        mListHolders.forEach { it.onDetach() }
        mListHolders.clear()
    }

    // ==================== НОВЫЙ МЕТОД submitList ====================
    fun submitList(newList: List<MessageView>) {
        val diffCallback = DiffUtilCallback(mListMessagesCache, newList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        mListMessagesCache.clear()
        mListMessagesCache.addAll(newList)
        diffResult.dispatchUpdatesTo(this)
    }
}

// ==================== DiffUtil для оптимизации ====================
private class DiffUtilCallback(
    private val oldList: List<MessageView>,
    private val newList: List<MessageView>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size
    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].text == newList[newItemPosition].text
    }
}