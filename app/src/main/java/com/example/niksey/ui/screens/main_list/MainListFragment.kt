package com.example.niksey.ui.screens.main_list

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.*
import com.example.niksey.models.CommonModel
import com.example.niksey.utillits.*

class MainListFragment : Fragment(R.layout.fragment_main_list) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mAdapter: MainListAdapter

    private val originalList = mutableListOf<CommonModel>()
    private val filteredList = mutableListOf<CommonModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        initRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.mToolbar?.title = getString(R.string.app_name)
        APP_ACTIVITY.mAppDrawer.enableDrawer()
    }

    private fun initRecyclerView() {
        mRecyclerView = requireView().findViewById(R.id.main_list_recycle_view)
        mAdapter = MainListAdapter()

        mRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mAdapter
            setHasFixedSize(true)
        }

        loadMainList()
    }

    private fun loadMainList() {
        originalList.clear()

        val mRefMainList = REF_DATABASE_ROOT.child(NODE_MAIN_LIST).child(CURRENT_UID)

        mRefMainList.addListenerForSingleValueEvent(AppValueEventListener { dataSnapshot ->
            dataSnapshot.children.forEach { snapshot ->
                val model = snapshot.getCommonModel()

                when (model.type) {
                    TYPE_CHAT -> loadChatWithLastMessage(model)
                    TYPE_GROUP -> loadGroupWithLastMessage(model)
                }
            }
        })
    }

    private fun loadChatWithLastMessage(model: CommonModel) {
        mRefUsers.child(model.id)
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot1 ->
                val userModel = snapshot1.getCommonModel()

                mRefMessages.child(model.id).limitToLast(1)
                    .addListenerForSingleValueEvent(AppValueEventListener { snapshot2 ->
                        val lastMsg = snapshot2.children.firstOrNull()?.getCommonModel()

                        if (lastMsg != null) {
                            userModel.lastMessage = decryptLastMessage(lastMsg, model.id)
                            userModel.timeStamp = lastMsg.timeStamp as? Long ?: 0L
                            userModel.type = lastMsg.type.ifEmpty { TYPE_CHAT }
                        } else {
                            userModel.lastMessage = getString(R.string.chat_cleared)
                            userModel.timeStamp = 0L
                            userModel.type = TYPE_CHAT
                        }

                        if (userModel.fullname.isEmpty()) userModel.fullname = userModel.phone
                        addItemSorted(userModel)
                    })
            })
    }

    private fun loadGroupWithLastMessage(model: CommonModel) {
        REF_DATABASE_ROOT.child(NODE_GROUPS).child(model.id)
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot1 ->
                val groupModel = snapshot1.getCommonModel()

                REF_DATABASE_ROOT.child(NODE_GROUPS).child(model.id).child(NODE_MESSAGES)
                    .limitToLast(1)
                    .addListenerForSingleValueEvent(AppValueEventListener { snapshot2 ->
                        val lastMsg = snapshot2.children.firstOrNull()?.getCommonModel()

                        if (lastMsg != null) {
                            groupModel.lastMessage = decryptLastMessage(lastMsg, model.id)
                            groupModel.timeStamp = lastMsg.timeStamp as? Long ?: 0L
                            groupModel.type = lastMsg.type.ifEmpty { TYPE_GROUP }
                        } else {
                            groupModel.lastMessage = getString(R.string.chat_cleared)
                            groupModel.timeStamp = 0L
                            groupModel.type = TYPE_GROUP
                        }

                        addItemSorted(groupModel)
                    })
            })
    }

    private fun addItemSorted(item: CommonModel) {
        originalList.add(item)

        val sortedList = originalList.sortedByDescending { it.timeStamp as? Long ?: 0L }

        originalList.clear()
        originalList.addAll(sortedList)

        filteredList.clear()
        filteredList.addAll(originalList)

        mAdapter.submitList(filteredList)
    }

    private fun decryptLastMessage(msg: CommonModel, chatId: String): String {
        val type = msg.type.lowercase()
        return when {
            type == TYPE_MESSAGE_VOICE.lowercase() || type.contains("voice") -> "🎤 Голосовое сообщение"
            type == TYPE_MESSAGE_IMAGE.lowercase() || type.contains("image") || type.contains("photo") -> "🖼️ Изображение"
            type == TYPE_MESSAGE_FILE.lowercase() || type.contains("file") -> "📎 Файл"
            else -> {
                if (msg.text.isBlank()) return "Нет сообщений"
                try {
                    val chatKey = ChatEncryptionManager.getChatKey(chatId)
                    if (chatKey != null) ChatEncryptionManager.decryptMessage(msg.text, chatKey) else msg.text
                } catch (e: Exception) {
                    msg.text
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main_list_menu, menu)

        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterList(newText)
                return true
            }
        })
    }

    private fun filterList(query: String?) {
        if (query.isNullOrBlank()) {
            filteredList.clear()
            filteredList.addAll(originalList)
        } else {
            val lowerQuery = query.lowercase()
            filteredList.clear()
            filteredList.addAll(
                originalList.filter {
                    it.fullname.lowercase().contains(lowerQuery) ||
                            it.phone.lowercase().contains(lowerQuery) ||
                            it.username.lowercase().contains(lowerQuery)
                }
            )
        }
        mAdapter.submitList(filteredList)
    }

    private val mRefUsers = REF_DATABASE_ROOT.child(NODE_USERS)
    private val mRefMessages = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID)
    private val mRefMainList = REF_DATABASE_ROOT.child(NODE_MAIN_LIST).child(CURRENT_UID)
}