package com.example.niksey.ui.screens.groups_messages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.models.CommonModel
import com.example.niksey.utillits.downloadAndSetImage
import de.hdodenhof.circleimageview.CircleImageView

@Suppress("DEPRECATION")
class AddContactsAdapter : RecyclerView.Adapter<AddContactsAdapter.AddContactsHolder>() {

    private val listItems = mutableListOf<CommonModel>()

    inner class AddContactsHolder(view: View) : RecyclerView.ViewHolder(view) {
        val itemName: TextView = view.findViewById(R.id.add_contacts_item_name)
        val itemLastMessage: TextView = view.findViewById(R.id.add_contacts_last_message)
        val itemPhoto: CircleImageView = view.findViewById(R.id.add_contacts_item_photo)
        val itemChoice: CircleImageView = view.findViewById(R.id.add_contacts_item_choice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddContactsHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.add_contacts_item, parent, false)
        return AddContactsHolder(view).apply {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = listItems[position]
                    if (item.choice) {
                        itemChoice.visibility = View.INVISIBLE
                        item.choice = false
                        AddContactsFragment.listContacts.remove(item)
                    } else {
                        itemChoice.visibility = View.VISIBLE
                        item.choice = true
                        AddContactsFragment.listContacts.add(item)
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = listItems.size

    override fun onBindViewHolder(holder: AddContactsHolder, position: Int) {
        val item = listItems[position]
        holder.itemName.text = item.fullname
        holder.itemLastMessage.text = item.lastMessage
        holder.itemPhoto.downloadAndSetImage(item.photoUrl)
    }

    fun updateListItems(item: CommonModel) {
        if (!listItems.contains(item)) {
            listItems.add(item)
            notifyItemInserted(listItems.lastIndex)
        }
    }

    // ПОИСК
    private var originalList = mutableListOf<CommonModel>()

}