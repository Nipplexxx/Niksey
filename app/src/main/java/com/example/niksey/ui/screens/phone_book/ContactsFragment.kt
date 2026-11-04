package com.example.niksey.ui.screens.phone_book

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.saveToMainList
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.objects.AppDrawer
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.example.niksey.ui.screens.profile.UserProfileSheet
import com.example.niksey.utillits.*
import com.firebase.ui.database.FirebaseRecyclerAdapter
import com.firebase.ui.database.FirebaseRecyclerOptions
import com.google.firebase.database.DatabaseReference
import android.widget.TextView

/**
 * Экран контактов.
 *
 * • Список берётся из main_list/{uid} — все личные чаты пользователя.
 * • Поиск — по username (Firebase startAt/endAt-префикс). Найденный контакт открывает чат
 *   и добавляется в main_list с обеих сторон (saveToMainList).
 * • FirebaseRecyclerAdapter слушает Firebase: startListening/stopListening строго парные —
 *   слушание останавливается в onPause и при смене запроса, иначе адаптер продолжал бы
 *   обновлять мёртвый RecyclerView (утечка).
 */
@Suppress("DEPRECATION")
class ContactsFragment : Fragment(R.layout.fragment_contacts) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mEmptyState: View
    private lateinit var mAdapter: FirebaseRecyclerAdapter<CommonModel, ContactBinding.ContactHolder>
    private lateinit var mRefMainList: DatabaseReference
    private var searchView: SearchView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.mToolbar.title = getString(R.string.contacts)
        APP_ACTIVITY.mAppDrawer.showRoot(AppDrawer.Tab.CONTACTS)
        initRecycleView()
        hideKeyboard()
    }

    private fun initRecycleView() {
        // Останавливаем прошлый адаптер, иначе он продолжает слушать Firebase (утечка)
        if (::mAdapter.isInitialized) mAdapter.stopListening()
        mRecyclerView = requireView().findViewById(R.id.contacts_recycle_view)
        mRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        mEmptyState = requireView().findViewById(R.id.contacts_empty_state)
        mEmptyState.findViewById<TextView>(R.id.empty_title).setText(R.string.empty_contacts_title)
        mEmptyState.findViewById<TextView>(R.id.empty_subtitle).setText(R.string.empty_contacts_subtitle)
        mRefMainList = REF_DATABASE_ROOT.child(NODE_MAIN_LIST).child(CURRENT_UID)

        // Контакты — только личные чаты (type = "chat"). Группы показываются
        // в списке чатов, здесь им не место.
        val options = FirebaseRecyclerOptions.Builder<CommonModel>()
            .setQuery(mRefMainList.orderByChild("type").equalTo(TYPE_CHAT), CommonModel::class.java)
            .build()

        mAdapter = object : FirebaseRecyclerAdapter<CommonModel, ContactBinding.ContactHolder>(options) {
            override fun onDataChanged() {
                mEmptyState.isVisible = itemCount == 0
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactBinding.ContactHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.contact_item, parent, false)
                return ContactBinding.createHolder(view)
            }

            override fun onBindViewHolder(
                holder: ContactBinding.ContactHolder,
                position: Int,
                model: CommonModel
            ) {
                // Исторически в main_list у личных чатов type мог попасть тип последнего
                // сообщения (text/voice/...) — всё это личные чаты, а не группы
                if (model.type.lowercase() == TYPE_GROUP) return

                ContactBinding.bind(holder, model.id)
                holder.itemView.setOnClickListener {
                    replaceFragment(SingleChatFragment.newInstance(model.id))
                }
                // Долгое нажатие: профиль контакта поверх списка (как в Telegram)
                holder.itemView.setOnLongClickListener {
                    UserProfileSheet.newInstance(model.id)
                        .show(parentFragmentManager, "user_profile")
                    true
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

    /** Поиск по префиксу username; выбранный контакт открывает чат и добавляется в main_list. */
    private fun searchUsers(query: String) {
        if (::mAdapter.isInitialized) mAdapter.stopListening()
        val searchQuery = REF_DATABASE_ROOT.child(NODE_USERS)
            .orderByChild("username")
            .startAt(query)
            .endAt(query + "\uf8ff")

        val options = FirebaseRecyclerOptions.Builder<CommonModel>()
            .setQuery(searchQuery, CommonModel::class.java)
            .build()

        mAdapter = object : FirebaseRecyclerAdapter<CommonModel, ContactBinding.ContactHolder>(options) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactBinding.ContactHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.contact_item, parent, false)
                return ContactBinding.createHolder(view)
            }

            override fun onBindViewHolder(
                holder: ContactBinding.ContactHolder,
                position: Int,
                model: CommonModel
            ) {
                if (model.id == CURRENT_UID) return // себя не показываем

                ContactBinding.bind(holder, model.id)
                holder.itemView.setOnClickListener {
                    try {
                        saveToMainList(model.id, TYPE_CHAT)
                        replaceFragment(SingleChatFragment.newInstance(model.id))
                        searchView?.setQuery("", false)
                        searchView?.clearFocus()
                        initRecycleView()
                    } catch (e: Exception) {
                        android.util.Log.e("ContactsFragment", "Ошибка при открытии чата из поиска", e)
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
}
