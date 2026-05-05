package com.example.niksey.ui.screens.groups_messages

import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.getCommonModel
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.AppValueEventListener
import com.example.niksey.utillits.CHILD_FULLNAME
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.NODE_GROUPS
import com.example.niksey.utillits.NODE_PARTICIPANTS
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.replaceFragment
import com.example.niksey.utillits.showToast

class GroupSettingsFragment(private val group: CommonModel) : BaseFragment(R.layout.fragment_group_settings) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mAdapter: GroupParticipantsAdapter
    private val participants = mutableListOf<CommonModel>()

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.title = getString(R.string.group_settings)
        initViews()
        loadParticipants()
    }

    private fun initViews() {
        // Имя группы
        view?.findViewById<EditText>(R.id.group_name_edit)?.setText(group.fullname)

        // Кнопка сохранить имя
        view?.findViewById<TextView>(R.id.btn_save_name)?.setOnClickListener {
            val newName = view?.findViewById<EditText>(R.id.group_name_edit)?.text.toString()
            if (newName.isNotBlank()) {
                updateGroupName(newName)
            }
        }

        // Добавить участников
        view?.findViewById<TextView>(R.id.btn_add_participants)?.setOnClickListener {
            replaceFragment(AddContactsFragment())
        }

        // RecyclerView участников
        mRecyclerView = view?.findViewById(R.id.participants_recycler)!!
        mAdapter = GroupParticipantsAdapter(participants) { user ->
            removeParticipant(user)
        }
        mRecyclerView.adapter = mAdapter
    }

    private fun loadParticipants() {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_PARTICIPANTS")
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                participants.clear()
                snapshot.children.forEach { child ->
                    val userId = child.key ?: return@forEach
                    REF_DATABASE_ROOT.child("$NODE_USERS/$userId")
                        .addListenerForSingleValueEvent(AppValueEventListener { userSnapshot ->
                            val user = userSnapshot.getCommonModel()
                            participants.add(user)
                            mAdapter.notifyDataSetChanged()
                        })
                }
            })
    }

    private fun updateGroupName(newName: String) {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$CHILD_FULLNAME")
            .setValue(newName)
            .addOnSuccessListener {
                showToast(getString(R.string.group_name_updated))
                group.fullname = newName
            }
    }

    private fun removeParticipant(user: CommonModel) {
        if (user.id == CURRENT_UID) {
            showToast(getString(R.string.cannot_remove_self))
            return
        }

        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_PARTICIPANTS/${user.id}")
            .removeValue()
            .addOnSuccessListener {
                showToast(getString(R.string.participant_removed))
                participants.remove(user)
                mAdapter.notifyDataSetChanged()
            }
    }
}