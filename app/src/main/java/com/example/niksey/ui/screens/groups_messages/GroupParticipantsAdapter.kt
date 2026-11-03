package com.example.niksey.ui.screens.groups_messages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.utillits.downloadAndSetImage

/** Участник группы для списка. */
data class GroupMember(
    val id: String,
    val name: String,
    val photoUrl: String,
    val isCreator: Boolean
)

/**
 * Список участников группы. Кнопка удаления видна только при [canRemove]
 * (то есть когда текущий пользователь — создатель группы).
 */
class GroupParticipantsAdapter(
    private val participants: List<GroupMember>,
    private val onRemoveClick: (GroupMember) -> Unit
) : RecyclerView.Adapter<GroupParticipantsAdapter.ParticipantHolder>() {

    var canRemove: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

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
        val member = participants[position]

        holder.name.text = if (member.isCreator) "${member.name} · создатель" else member.name
        holder.photo.downloadAndSetImage(member.photoUrl)

        // Удалять можно участников, но не себя и не создателя
        val removable = canRemove && !member.isCreator
        holder.btnRemove.visibility = if (removable) View.VISIBLE else View.GONE
        holder.btnRemove.setOnClickListener { onRemoveClick(member) }
    }
}
