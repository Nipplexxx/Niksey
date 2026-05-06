package com.example.niksey.ui.screens.private_messages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.ui.fragments.message_recycler_view.view_holders.AppHolderFactory
import com.example.niksey.ui.fragments.message_recycler_view.view_holders.HolderVideoMessage
import com.example.niksey.ui.fragments.message_recycler_view.view_holders.MessageHolder
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView

@Suppress("DEPRECATION")
class SingleChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val mListMessagesCache = mutableListOf<MessageView>()
    private val mListHolders = mutableListOf<MessageHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return try {
            android.util.Log.d("SingleChatAdapter", "Создаём холдер для viewType = $viewType")
            AppHolderFactory.getHolder(parent, viewType)
        } catch (e: Exception) {
            android.util.Log.e("SingleChatAdapter", "КРАШ при создании холдера viewType=$viewType", e)
            when (viewType) {
                4 -> HolderVideoMessage(LayoutInflater.from(parent.context).inflate(com.example.niksey.R.layout.message_item_video, parent, false))
                else -> object : RecyclerView.ViewHolder(View(parent.context)) {}
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
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
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION && pos in mListMessagesCache.indices) {
                holder.onAttach(mListMessagesCache[pos])
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

    fun submitList(newList: List<MessageView>) {
        val diff = DiffUtil.calculateDiff(DiffUtilCallback(mListMessagesCache, newList))
        mListMessagesCache.clear()
        mListMessagesCache.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    fun onDestroy() {
        mListHolders.forEach { it.onDetach() }
        mListHolders.clear()
    }
}

private class DiffUtilCallback(
    private val oldList: List<MessageView>,
    private val newList: List<MessageView>
) : DiffUtil.Callback() {
    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size
    override fun areItemsTheSame(old: Int, new: Int) = oldList[old].id == newList[new].id
    override fun areContentsTheSame(old: Int, new: Int) = oldList[old].text == newList[new].text
}