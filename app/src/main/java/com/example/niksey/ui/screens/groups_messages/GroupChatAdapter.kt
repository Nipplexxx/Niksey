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
        // DiffUtil может спросить тип для позиции, которой уже нет (расшифровка обновляет список)
        return if (position in mListMessagesCache.indices) mListMessagesCache[position].getTypeView() else -1
    }

    override fun getItemCount(): Int = mListMessagesCache.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is MessageHolder && position in mListMessagesCache.indices) {
            holder.drawMessage(mListMessagesCache[position])
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is MessageHolder) {
            val position = holder.adapterPosition
            if (position != RecyclerView.NO_POSITION && position in mListMessagesCache.indices) {
                holder.onAttach(mListMessagesCache[position])
                mListHolders.add(holder)
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is MessageHolder) {
            holder.onDetach()
            mListHolders.remove(holder)
        }
    }

    fun onDestroy() {
        mListHolders.forEach { it.onDetach() }
        mListHolders.clear()
    }

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
        return oldList[oldItemPosition].text == newList[newItemPosition].text &&
                oldList[oldItemPosition].decryptedText == newList[newItemPosition].decryptedText
    }
}