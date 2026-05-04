package com.example.niksey.ui.screens.groups_messages

import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.*
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.utillits.*
import com.mikepenz.materialize.util.KeyboardUtil.hideKeyboard

class AddContactsFragment : BaseFragment(R.layout.fragment_add_contacts) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mAdapter: AddContactsAdapter
    private val mRefContactsList = REF_DATABASE_ROOT.child(NODE_PHONES_CONTACTS).child(CURRENT_UID)
    private val mRefUsers = REF_DATABASE_ROOT.child(NODE_USERS)
    private val mRefMessages = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID)

    override fun onResume() {
        listContacts.clear()
        super.onResume()
        APP_ACTIVITY.title = getString(R.string.add_a_participant)
        hideKeyboard(activity)
        initRecyclerView()

        view?.findViewById<Button>(R.id.add_contacts_btn_next)?.setOnClickListener {
            if (listContacts.isEmpty()) {
                showToast(getString(R.string.add_participant))
            } else {
                replaceFragment(CreateGroupFragment(listContacts))
            }
        }
    }

    private fun initRecyclerView() {
        mRecyclerView = view?.findViewById(R.id.add_contacts_recycle_view)!!
        mAdapter = AddContactsAdapter()
        mRecyclerView.adapter = mAdapter

        mRefContactsList.addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
            val contacts = snapshot.children.map { it.getCommonModel() }

            contacts.forEach { contact ->
                mRefUsers.child(contact.id).addListenerForSingleValueEvent(AppValueEventListener { userSnapshot ->
                    val user = userSnapshot.getCommonModel()

                    mRefMessages.child(contact.id).limitToLast(1).addListenerForSingleValueEvent(AppValueEventListener { msgSnapshot ->
                        val messages = msgSnapshot.children.map { it.getCommonModel() }
                        user.lastMessage = if (messages.isEmpty()) getString(R.string.chat_cleared) else messages[0].text
                        if (user.fullname.isEmpty()) user.fullname = user.phone
                        mAdapter.updateListItems(user)
                    })
                })
            }
        })
    }

    companion object {
        val listContacts = mutableListOf<CommonModel>()
    }
}