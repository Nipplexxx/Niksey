package com.example.niksey.ui.screens.phone_book

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.*
import com.example.niksey.models.CommonModel
import com.example.niksey.models.UserModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.example.niksey.utillits.*
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.google.firebase.database.DatabaseReference
import de.hdodenhof.circleimageview.CircleImageView

@Suppress("DEPRECATION")
class ContactsFragment : BaseFragment(R.layout.fragment_contacts) {

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
        APP_ACTIVITY.mToolbar.title = getString(R.string.contacts)
        initRecycleView()
        hideKeyboard()
    }

    private fun initRecycleView() {
        mRecyclerView = requireView().findViewById(R.id.contacts_recycle_view)
        mRefMainList = REF_DATABASE_ROOT.child(NODE_MAIN_LIST).child(CURRENT_UID)

        val options = FirebaseRecyclerOptions.Builder<CommonModel>()
            .setQuery(mRefMainList, CommonModel::class.java)
            .build()

        mAdapter = object : FirebaseRecyclerAdapter<CommonModel, ContactsHolder>(options) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactsHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.contact_item, parent, false)
                return ContactsHolder(view)
            }

            override fun onBindViewHolder(holder: ContactsHolder, position: Int, model: CommonModel) {
                REF_DATABASE_ROOT.child(NODE_USERS).child(model.id)
                    .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                        val user = snapshot.getValue(UserModel::class.java) ?: return@AppValueEventListener
                        holder.name.text = if (user.fullname.isNotEmpty()) user.fullname else user.username
                        holder.status.text = user.getStateText()
                        holder.photo.downloadAndSetImage(user.photoUrl)
                    })

                holder.itemView.setOnClickListener {
                    replaceFragment(SingleChatFragment.newInstance(model.id))   // ← ИСПРАВЛЕНО
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
                REF_DATABASE_ROOT.child(NODE_USERS).child(model.id)
                    .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                        val user = snapshot.getValue(UserModel::class.java) ?: return@AppValueEventListener
                        holder.name.text = if (user.fullname.isNotEmpty()) user.fullname else user.username
                        holder.status.text = user.getStateText()
                        holder.photo.downloadAndSetImage(user.photoUrl)
                    })

                holder.itemView.setOnClickListener {
                    try {
                        saveToMainList(model.id, TYPE_CHAT)
                        replaceFragment(SingleChatFragment.newInstance(model.id))   // ← ИСПРАВЛЕНО
                        searchView?.setQuery("", false)
                        searchView?.clearFocus()
                        initRecycleView()
                    } catch (e: Exception) {
                        Log.e("ContactsFragment", "Ошибка при открытии чата из поиска", e)
                        showToast("Ошибка при открытии чата")
                    }
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
    }
}