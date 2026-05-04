package com.example.niksey.ui.screens.main_list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.screens.groups_messages.GroupChatFragment
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.example.niksey.utillits.*
import com.example.niksey.utillits.replaceFragment
import de.hdodenhof.circleimageview.CircleImageView

class MainListAdapter : RecyclerView.Adapter<MainListAdapter.MainListHolder>() {

    private var listItems = mutableListOf<CommonModel>()

    class MainListHolder(view: View) : RecyclerView.ViewHolder(view) {
        val itemName: TextView? = view.findViewById(R.id.main_list_item_name)
        val itemLastMessage: TextView = view.findViewById(R.id.main_list_last_message)
        val itemPhoto: CircleImageView = view.findViewById(R.id.main_list_item_photo)
        val itemTime: TextView = view.findViewById(R.id.main_list_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainListHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.main_list_item, parent, false)
        return MainListHolder(view)
    }

    override fun getItemCount(): Int = listItems.size

    override fun onBindViewHolder(holder: MainListHolder, position: Int) {
        val item = listItems[position]

        holder.itemName?.text = item.fullname.ifEmpty { item.username }
        holder.itemLastMessage.text = item.lastMessage.ifEmpty { "Нет сообщений" }
        holder.itemPhoto.downloadAndSetImage(item.photoUrl)

        holder.itemTime.text = if (item.timeStamp.toString().isNotBlank()) {
            item.timeStamp.toString().asTime()
        } else {
            ""
        }

        holder.itemView.setOnClickListener {
            val type = item.type.lowercase()

            when {
                type == TYPE_CHAT.lowercase() ||
                        type == "text" ||
                        type == TYPE_MESSAGE_VOICE.lowercase() ||
                        type == TYPE_MESSAGE_IMAGE.lowercase() ||
                        type == TYPE_MESSAGE_FILE.lowercase() -> {
                    replaceFragment(SingleChatFragment(item))
                }
                type == TYPE_GROUP.lowercase() -> {
                    replaceFragment(GroupChatFragment(item))
                }
                else -> {
                    showToast("Неизвестный тип чата: ${item.type}")
                }
            }
        }
    }

    // Добавляет один элемент
    fun updateListItems(item: CommonModel) {
        listItems.add(item)
        notifyItemInserted(listItems.size - 1)
    }

    // Полностью заменяет список (для сортировки и поиска)
    fun submitList(newList: List<CommonModel>) {
        listItems.clear()
        listItems.addAll(newList)
        notifyDataSetChanged()
    }
}