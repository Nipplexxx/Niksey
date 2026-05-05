package com.example.niksey.ui.screens.groups_messages

import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.*
import com.example.niksey.models.CommonModel
import com.example.niksey.models.UserModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.utillits.*
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.google.firebase.database.DatabaseReference
import com.mikepenz.materialize.util.KeyboardUtil.hideKeyboard
import de.hdodenhof.circleimageview.CircleImageView

@Suppress("DEPRECATION")
class AddContactsFragment : BaseFragment(R.layout.fragment_add_contacts) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mAdapter: FirebaseRecyclerAdapter<CommonModel, ContactsHolder>
    private lateinit var mRefMainList: DatabaseReference
    private var searchView: SearchView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.title = getString(R.string.add_a_participant)
        hideKeyboard(activity)
        initRecycleView()

        // Кнопка Next
        view?.findViewById<com.google.android.material.button.MaterialButton>(R.id.add_contacts_btn_next)?.setOnClickListener {
            if (listContacts.isEmpty()) {
                showToast(getString(R.string.add_participant))
            } else {
                replaceFragment(CreateGroupFragment(listContacts))
            }
        }
    }

    private fun initRecycleView() {
        mRecyclerView = requireView().findViewById(R.id.add_contacts_recycle_view)
        mRefMainList = REF_DATABASE_ROOT.child(NODE_MAIN_LIST).child(CURRENT_UID)

        val options = FirebaseRecyclerOptions.Builder<CommonModel>()
            .setQuery(mRefMainList.orderByChild("type").equalTo(TYPE_CHAT), CommonModel::class.java)
            .build()

        mAdapter = object : FirebaseRecyclerAdapter<CommonModel, ContactsHolder>(options) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactsHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.contact_item, parent, false)
                return ContactsHolder(view)
            }

            override fun onBindViewHolder(holder: ContactsHolder, position: Int, model: CommonModel) {
                if (model.id == CURRENT_UID) return // не показываем себя

                REF_DATABASE_ROOT.child(NODE_USERS).child(model.id)
                    .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                        val user = snapshot.getValue(UserModel::class.java) ?: return@AppValueEventListener
                        holder.name.text = user.fullname.ifEmpty { user.username }
                        holder.status.text = user.getStateText()
                        holder.photo.downloadAndSetImage(user.photoUrl)
                    })

                holder.itemView.setOnClickListener {
                    if (listContacts.contains(model)) {
                        listContacts.remove(model)
                        holder.checkmark.visibility = View.GONE
                    } else {
                        listContacts.add(model)
                        holder.checkmark.visibility = View.VISIBLE
                    }
                }
            }
        }

        mRecyclerView.adapter = mAdapter
        mAdapter.startListening()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.contacts_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem.actionView as SearchView

        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrBlank()) searchUsers(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank()) {
                    initRecycleView()
                } else {
                    searchUsers(newText)
                }
                return true
            }
        })
    }

    private fun searchUsers(query: String) {
        val searchQuery = REF_DATABASE_ROOT.child(NODE_USERS)
            .orderByChild("username")
            .startAt(query)
            .endAt(query + "\uf8ff")

        val options = FirebaseRecyclerOptions.Builder<CommonModel>()
            .setQuery(searchQuery, CommonModel::class.java)
            .build()

        mAdapter = object : FirebaseRecyclerAdapter<CommonModel, ContactsHolder>(options) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactsHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.contact_item, parent, false)
                return ContactsHolder(view)
            }

            override fun onBindViewHolder(holder: ContactsHolder, position: Int, model: CommonModel) {
                if (model.id == CURRENT_UID) return // не показываем себя

                REF_DATABASE_ROOT.child(NODE_USERS).child(model.id)
                    .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                        val user = snapshot.getValue(UserModel::class.java) ?: return@AppValueEventListener

                        holder.name.text = user.fullname.ifEmpty { user.username }
                        holder.status.text = user.getStateText()
                        holder.photo.downloadAndSetImage(user.photoUrl)
                    })

                holder.itemView.setOnClickListener {
                    if (listContacts.contains(model)) {
                        listContacts.remove(model)
                        holder.checkmark.visibility = View.GONE
                    } else {
                        listContacts.add(model)
                        holder.checkmark.visibility = View.VISIBLE
                    }

                    searchView?.setQuery("", false)
                    searchView?.clearFocus()
                }
            }
        }

        mRecyclerView.adapter = mAdapter
        mAdapter.startListening()
    }

    override fun onPause() {
        super.onPause()
        if (::mAdapter.isInitialized) {
            mAdapter.stopListening()
        }
    }

    class ContactsHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.contact_fullname)
        val status: TextView = view.findViewById(R.id.contact_status)
        val photo: CircleImageView = view.findViewById(R.id.contact_photo)
        val checkmark: ImageView = view.findViewById(R.id.contact_checkmark) // добавь в layout
    }

    companion object {
        val listContacts = mutableListOf<CommonModel>()
    }
}