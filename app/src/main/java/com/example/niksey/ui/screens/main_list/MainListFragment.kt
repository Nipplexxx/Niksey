@file:Suppress("DEPRECATION")

package com.example.niksey.ui.screens.main_list

import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.*
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.screens.groups_messages.AddContactsFragment
import com.example.niksey.ui.objects.AppDrawer
import com.example.niksey.utillits.*
import com.example.niksey.utillits.signal.SecureMessaging

class MainListFragment : Fragment(R.layout.fragment_main_list) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mAdapter: MainListAdapter
    private lateinit var mEmptyState: View

    private val originalList = mutableListOf<CommonModel>()
    private val filteredList = mutableListOf<CommonModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        initRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.mToolbar.title = getString(R.string.app_name)
        APP_ACTIVITY.mAppDrawer.showRoot(AppDrawer.Tab.CHATS)
    }

    private fun initRecyclerView() {
        mRecyclerView = requireView().findViewById(R.id.main_list_recycle_view)
        mEmptyState = requireView().findViewById(R.id.main_empty_state)
        requireView().findViewById<View>(R.id.main_fab_create_group).setOnClickListener {
            replaceFragment(AddContactsFragment())
        }
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
            val chatIds = mutableListOf<String>()

            dataSnapshot.children.forEach { snapshot ->
                val model = snapshot.getCommonModel()
                chatIds.add(model.id)

                when (model.type) {
                    TYPE_CHAT -> loadChatWithLastMessage(model)
                    TYPE_GROUP -> loadGroupWithLastMessage(model)
                }
            }

            updateEmptyState()

            // Предзагружаем все публичные ключи сразу
            chatIds.forEach { chatId ->
                ChatEncryptionManager.getOtherUserPublicKey(chatId) { /* кэшируем */ }
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
                            decryptLastMessage(lastMsg, ChatEncryptionManager.privateConversationId(CURRENT_UID, model.id)) { decryptedText ->
                                userModel.lastMessage = decryptedText
                                userModel.timeStamp = lastMsg.timeStamp as? Long ?: 0L
                                userModel.type = lastMsg.type.ifEmpty { TYPE_CHAT }

                                if (userModel.fullname.isEmpty()) userModel.fullname = userModel.phone
                                addItemSorted(userModel)
                            }
                        } else {
                            userModel.lastMessage = getString(R.string.chat_cleared)
                            userModel.timeStamp = 0L
                            userModel.type = TYPE_CHAT
                            if (userModel.fullname.isEmpty()) userModel.fullname = userModel.phone
                            addItemSorted(userModel)
                        }
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
                            decryptLastMessage(lastMsg, ChatEncryptionManager.groupConversationId(model.id)) { decryptedText ->
                                groupModel.lastMessage = decryptedText
                                groupModel.timeStamp = lastMsg.timeStamp as? Long ?: 0L
                                groupModel.type = lastMsg.type.ifEmpty { TYPE_GROUP }
                                addItemSorted(groupModel)
                            }
                        } else {
                            groupModel.lastMessage = getString(R.string.chat_cleared)
                            groupModel.timeStamp = 0L
                            groupModel.type = TYPE_GROUP
                            addItemSorted(groupModel)
                        }
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
        updateEmptyState()
    }

    /** Показываем оформленную заглушку только когда чатов нет вообще (не при пустом поиске). */
    private fun updateEmptyState() {
        mEmptyState.isVisible = originalList.isEmpty()
        mRecyclerView.isVisible = originalList.isNotEmpty()
    }

    private fun decryptLastMessage(msg: CommonModel, conversationId: String, onResult: (String) -> Unit) {
        val type = msg.type.lowercase()
        val fileUrl = msg.fileUrl.lowercase()

        // === ДЛЯ МЕДИА — ВСЕГДА ПОКАЗЫВАЕМ ТИП (даже если text пустой) ===
        when {
            type.contains("voice") || fileUrl.contains("voice") -> {
                onResult("🎤 Голосовое сообщение")
                return
            }
            type.contains("image") || type.contains("photo") || fileUrl.contains("image") || fileUrl.contains("photo") -> {
                onResult("🖼️ Изображение")
                return
            }
            type.contains("file") || fileUrl.contains("file") -> {
                onResult("📎 Файл")
                return
            }
        }

        // === ДЛЯ ТЕКСТА ===
        if (msg.text.isBlank()) {
            onResult("Нет сообщений")
            return
        }

        // Для текста — расшифровываем
        if (msg.decryptedText.isNotEmpty()) {
            onResult(msg.decryptedText)
            return
        }

        // Превью: расшифровываем ключом беседы; без ключа/при ошибке — заглушка, а не шифротекст
        fun preview(decrypted: String?): String = when {
            decrypted == null -> ChatEncryptionManager.UNREADABLE_TEXT
            decrypted.length > 50 -> decrypted.take(50) + "..."
            else -> decrypted
        }

        // Signal-сообщение расшифровывается один раз и кэшируется по id — повторный вызов берёт кэш
        SecureMessaging.decryptText(msg.id, msg.from, conversationId, msg.text) { decrypted ->
            if (decrypted != null) msg.decryptedText = decrypted
            onResult(preview(decrypted))
        }
    }

    @Deprecated("Deprecated in Java")
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
}