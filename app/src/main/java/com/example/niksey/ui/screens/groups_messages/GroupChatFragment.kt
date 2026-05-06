@file:Suppress("UNCHECKED_CAST", "DEPRECATION")
package com.example.niksey.ui.screens.groups_messages

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.niksey.R
import com.example.niksey.database.*
import com.example.niksey.models.CommonModel
import com.example.niksey.models.UserModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.ui.viewmodels.ChatViewModel
import com.example.niksey.utillits.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.database.DatabaseReference

class GroupChatFragment(private val group: CommonModel) : BaseFragment(R.layout.fragment_chat) {

    private val viewModel: ChatViewModel by viewModels()

    private lateinit var mListenerInfoToolbar: AppValueEventListener
    private lateinit var mReceivingUser: UserModel
    private lateinit var mToolbarInfo: View
    private lateinit var mRefUser: DatabaseReference
    private lateinit var mAdapter: GroupChatAdapter
    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout
    private lateinit var mLayoutManager: LinearLayoutManager
    private lateinit var mAppVoiceRecorder: AppVoiceRecorder
    private lateinit var mBottomSheetBehavior: BottomSheetBehavior<*>
    private var participantsCount = 0

    private lateinit var cropImageLauncher: ActivityResultLauncher<CropImageContractOptions>
    private lateinit var pickFileLauncher: ActivityResultLauncher<Intent>

    // ==================== REPLY SYSTEM ====================
    private var replyingToMessageId: String? = null
    private var replyingToText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val uri = result.uriContent ?: return@registerForActivityResult
                val messageKey = getMessageKeyGroup(group.id)
                uploadFileToStorageGroup(uri, messageKey, group.id, TYPE_MESSAGE_IMAGE) {
                    viewModel.reloadMessages()
                }
            } else {
                showToast(getString(R.string.error_cropping_image))
            }
        }

        pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                val messageKey = getMessageKeyGroup(group.id)
                val filename = getFilenameFromUri(uri)
                uploadFileToStorageGroup(uri, messageKey, group.id, TYPE_MESSAGE_FILE, filename) {
                    viewModel.reloadMessages()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        initFields()
        initToolbar()
        initRecycleView()
        loadParticipantsCount()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFields() {
        mAppVoiceRecorder = AppVoiceRecorder()
        mLayoutManager = LinearLayoutManager(requireContext())
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        // TextWatcher
        requireView().findViewById<EditText>(R.id.chat_input_message)
            .addTextChangedListener(AppTextWatcher { text ->
                val sendBtn = requireView().findViewById<ImageView>(R.id.chat_btn_send_message)
                val attachBtn = requireView().findViewById<ImageView>(R.id.chat_btn_attach)
                val voiceBtn = requireView().findViewById<ImageView>(R.id.chat_btn_voice)

                val textStr = text?.toString() ?: ""
                val isEmpty = textStr.isEmpty() || textStr == getString(R.string.record)

                sendBtn?.visibility = if (isEmpty) View.GONE else View.VISIBLE
                attachBtn?.visibility = if (isEmpty) View.VISIBLE else View.GONE
                voiceBtn?.visibility = if (isEmpty) View.VISIBLE else View.GONE
            })

        // Кнопка прикрепления
        requireView().findViewById<ImageView>(R.id.chat_btn_attach)?.setOnClickListener { attach() }

        // Кнопка отправки — исправленная версия
        requireView().findViewById<ImageView>(R.id.chat_btn_send_message)?.setOnClickListener {
            val message = requireView().findViewById<EditText>(R.id.chat_input_message)?.text?.toString()?.trim() ?: ""
            if (message.isNotEmpty()) {
                viewModel.sendMessage(message, replyingToMessageId) {
                    requireView().findViewById<EditText>(R.id.chat_input_message)?.setText("")
                    cancelReply()
                }
            }
        }

        // Голосовые сообщения
        requireView().findViewById<ImageView>(R.id.chat_btn_voice)?.setOnTouchListener { _, event ->
            if (checkPermission(RECORD_AUDIO)) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        requireView().findViewById<EditText>(R.id.chat_input_message)?.setText(getString(R.string.record))
                        requireView().findViewById<ImageView>(R.id.chat_btn_voice)?.setColorFilter(
                            ContextCompat.getColor(APP_ACTIVITY, R.color.purple_300)
                        )
                        val messageKey = getMessageKeyGroup(group.id)
                        mAppVoiceRecorder.startRecord(messageKey)
                    }
                    MotionEvent.ACTION_UP -> {
                        requireView().findViewById<EditText>(R.id.chat_input_message)?.setText("")
                        requireView().findViewById<ImageView>(R.id.chat_btn_voice)?.colorFilter = null
                        mAppVoiceRecorder.stopRecord { file, messageKey ->
                            uploadFileToStorageGroup(Uri.fromFile(file), messageKey, group.id, TYPE_MESSAGE_VOICE) {
                                viewModel.reloadMessages()
                            }
                        }
                    }
                }
            }
            true
        }
    }

    // ==================== REPLY SYSTEM (как в SingleChatFragment) ====================

    fun startReply(messageId: String, messageText: String, fromUser: String) {
        replyingToMessageId = messageId
        replyingToText = messageText.take(70) + if (messageText.length > 70) "..." else ""
        showReplyPreview(fromUser)
    }

    private fun showReplyPreview(fromUser: String) {
        val replyPreview = requireView().findViewById<View>(R.id.reply_preview) ?: return
        val replyTextView = replyPreview.findViewById<TextView>(R.id.reply_text)
        val closeBtn = replyPreview.findViewById<ImageView>(R.id.reply_close)

        replyTextView.text = "↩️ $fromUser: $replyingToText"
        replyPreview.visibility = View.VISIBLE

        closeBtn.setOnClickListener { cancelReply() }
    }

    private fun cancelReply() {
        replyingToMessageId = null
        replyingToText = null
        requireView().findViewById<View>(R.id.reply_preview)?.visibility = View.GONE
    }

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ДЛЯ ХОЛДЕРОВ ====================

    fun deleteMessage(messageId: String, fromUserId: String) {
        val path = "$NODE_GROUPS/${group.id}/$NODE_MESSAGES/$messageId"
        REF_DATABASE_ROOT.child(path).removeValue()
            .addOnSuccessListener {
                showToast("Сообщение удалено")
                viewModel.reloadMessages()
            }
            .addOnFailureListener {
                showToast("Не удалось удалить сообщение")
            }
    }

    fun scrollToMessage(messageId: String) {
        val messages = viewModel.messages.value ?: return
        val position = messages.indexOfFirst { it.id == messageId }
        if (position >= 0) {
            mRecyclerView.smoothScrollToPosition(position)
        } else {
            showToast("Сообщение не найдено")
        }
    }

    private fun attach() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun attachToClose() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun attachFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        pickFileLauncher.launch(intent)
    }

    private fun attachImage() {
        val options = CropImageContractOptions(
            uri = null,
            cropImageOptions = CropImageOptions(
                aspectRatioX = 1,
                aspectRatioY = 1,
                fixAspectRatio = true,
                outputRequestWidth = 250,
                outputRequestHeight = 250,
                cropShape = CropImageView.CropShape.OVAL
            )
        )
        cropImageLauncher.launch(options)
    }

    private fun initRecycleView() {
        mRecyclerView = requireView().findViewById(R.id.chat_recycle_view)
        mAdapter = GroupChatAdapter()
        mRecyclerView.apply {
            adapter = mAdapter
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
            layoutManager = mLayoutManager
        }

        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            mAdapter.submitList(messages)
            if (messages.isNotEmpty()) mRecyclerView.smoothScrollToPosition(messages.size - 1)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            mSwipeRefreshLayout.isRefreshing = isLoading
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                showToast(it)
                viewModel.clearError()
            }
        }

        mSwipeRefreshLayout.setOnRefreshListener {
            viewModel.loadMoreMessages()
        }

        viewModel.initChat(group.id, isGroup = true)
    }

    private fun loadParticipantsCount() {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_PARTICIPANTS")
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                participantsCount = snapshot.childrenCount.toInt()
                APP_ACTIVITY.title = "${group.fullname} ($participantsCount)"
            })
    }

    private fun initToolbar() {
        mToolbarInfo = APP_ACTIVITY.mToolbar.findViewById(R.id.toolbar_info)
        mToolbarInfo.visibility = View.VISIBLE

        mListenerInfoToolbar = AppValueEventListener {
            mReceivingUser = it.getUserModel()
            initInfoToolbar()
        }

        mRefUser = REF_DATABASE_ROOT.child(NODE_USERS).child(group.id)
        mRefUser.addValueEventListener(mListenerInfoToolbar)

        preloadGroupMembersPublicKeys()
    }

    private fun preloadGroupMembersPublicKeys() {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_MEMBERS")
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                snapshot.children.forEach { member ->
                    val memberId = member.key ?: return@forEach
                    if (memberId != CURRENT_UID) {
                        ChatEncryptionManager.getOtherUserPublicKey(memberId) { publicKey ->
                            if (!publicKey.isNullOrEmpty()) {
                                ChatEncryptionManager.cachePublicKey(memberId, publicKey)
                            }
                        }
                    }
                }
            })
    }

    private fun initInfoToolbar() {
        val name = mReceivingUser.fullname.ifEmpty { group.fullname }
        mToolbarInfo.findViewById<TextView>(R.id.toolbar_chat_fullname).text = name
        mToolbarInfo.findViewById<ImageView>(R.id.toolbar_chat_image).downloadAndSetImage(group.photoUrl)
        mToolbarInfo.findViewById<TextView>(R.id.toolbar_chat_status).text = mReceivingUser.getStateText()
    }

    override fun onPause() {
        super.onPause()
        mToolbarInfo.visibility = View.GONE
        mRefUser.removeEventListener(mListenerInfoToolbar)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mAppVoiceRecorder.releaseRecorder()
        mAdapter.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        activity?.menuInflater?.inflate(R.menu.single_chat_action_menu, menu)
        menu.add(0, 1001, 0, getString(R.string.group_settings))
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_chat -> clearChatGroup(group.id) {
                showToast(getString(R.string.chat_cleared))
                replaceFragment(MainListFragment())
            }
            R.id.menu_remove_chat -> removeChatGroup(group.id) {
                showToast(getString(R.string.chat_remove))
                replaceFragment(MainListFragment())
            }
            R.id.menu_delete_chat -> deleteChatGroup(group.id) {
                showToast(getString(R.string.chat_deleted))
                replaceFragment(MainListFragment())
            }
            1001 -> replaceFragment(GroupSettingsFragment(group))
        }
        return true
    }
}