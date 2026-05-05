package com.example.niksey.ui.screens.groups_messages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.models.CommonModel
import com.example.niksey.utillits.downloadAndSetImage

class GroupParticipantsAdapter(
    private val participants: MutableList<CommonModel>,
    private val onRemoveClick: (CommonModel) -> Unit
) : RecyclerView.Adapter<GroupParticipantsAdapter.ParticipantHolder>() {

    inner class ParticipantHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.participant_name)
        val photo: ImageView = view.findViewById(R.id.participant_photo)
        val btnRemove: ImageView = view.findViewById(R.id.btn_remove_participant)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.participant_item, parent, false)
        return ParticipantHolder(view)
    }

    override fun getItemCount(): Int = participants.size

    override fun onBindViewHolder(holder: ParticipantHolder, position: Int) {
        val user = participants[position]

        holder.name.text = user.fullname.ifEmpty { user.username }
        holder.photo.downloadAndSetImage(user.photoUrl)

        // Кнопка удаления
        holder.btnRemove.setOnClickListener {
            onRemoveClick(user)
        }
    }
}