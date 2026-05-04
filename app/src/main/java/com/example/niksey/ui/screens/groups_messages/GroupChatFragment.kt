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
import com.example.niksey.ui.fragments.message_recycler_view.views.AppViewFactory
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.utillits.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.database.DatabaseReference

class GroupChatFragment(private val group: CommonModel) : BaseFragment(R.layout.fragment_chat) {

    private lateinit var mListenerInfoToolbar: AppValueEventListener
    private lateinit var mReceivingUser: UserModel
    private lateinit var mToolbarInfo: View
    private lateinit var mRefUser: DatabaseReference
    private lateinit var mRefMessages: DatabaseReference
    private lateinit var mAdapter: GroupChatAdapter
    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mMessagesListener: AppChildEventListener
    private var mCountMessages = 10
    private var mIsScrolling = false
    private var mSmoothScrollToPosition = true
    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout
    private lateinit var mLayoutManager: LinearLayoutManager
    private lateinit var mAppVoiceRecorder: AppVoiceRecorder
    private lateinit var mBottomSheetBehavior: BottomSheetBehavior<*>
    private lateinit var cropImageLauncher: ActivityResultLauncher<CropImageContractOptions>
    private lateinit var pickFileLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val uri = result.uriContent ?: return@registerForActivityResult
                val messageKey = getMessageKeyGroup(group.id)
                uploadFileToStorageGroup(uri, messageKey, group.id, TYPE_MESSAGE_IMAGE)
                mSmoothScrollToPosition = true
            } else {
                showToast(getString(R.string.error_cropping_image))
            }
        }

        pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                val messageKey = getMessageKeyGroup(group.id)
                val filename = getFilenameFromUri(uri)
                uploadFileToStorageGroup(uri, messageKey, group.id, TYPE_MESSAGE_FILE, filename)
                mSmoothScrollToPosition = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        initFields()
        initToolbar()
        initRecycleView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFields() {
        setHasOptionsMenu(true)
        mBottomSheetBehavior = BottomSheetBehavior.from(requireView().findViewById(R.id.bottom_sheet_choice))
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        mAppVoiceRecorder = AppVoiceRecorder()
        mSwipeRefreshLayout = requireView().findViewById(R.id.chat_swipe_refresh)
        mLayoutManager = LinearLayoutManager(requireContext())

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

        requireView().findViewById<ImageView>(R.id.chat_btn_attach)?.setOnClickListener { attach() }

        requireView().findViewById<ImageView>(R.id.chat_btn_send_message)?.setOnLongClickListener {
            APP_ACTIVITY.showAIQuickReplies { reply ->
                requireView().findViewById<EditText>(R.id.chat_input_message)?.setText(reply)
            }
            true
        }

        requireView().findViewById<ImageView>(R.id.chat_btn_voice)?.setOnTouchListener { _, event ->
            if (checkPermission(RECORD_AUDIO)) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        requireView().findViewById<EditText>(R.id.chat_input_message)?.setText(getString(R.string.record))
                        requireView().findViewById<ImageView>(R.id.chat_btn_voice)?.setColorFilter(
                            ContextCompat.getColor(APP_ACTIVITY, R.color.purple_200)
                        )
                        val messageKey = getMessageKeyGroup(group.id)
                        mAppVoiceRecorder.startRecord(messageKey)
                    }
                    MotionEvent.ACTION_UP -> {
                        requireView().findViewById<EditText>(R.id.chat_input_message)?.setText("")
                        requireView().findViewById<ImageView>(R.id.chat_btn_voice)?.colorFilter = null
                        mAppVoiceRecorder.stopRecord { file, messageKey ->
                            uploadFileToStorageGroup(Uri.fromFile(file), messageKey, group.id, TYPE_MESSAGE_VOICE)
                            mSmoothScrollToPosition = true
                        }
                    }
                }
            }
            true
        }
    }

    private fun attach() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        requireView().findViewById<ImageView>(R.id.btn_attach_file)?.setOnClickListener { attachFile() }
        requireView().findViewById<ImageView>(R.id.btn_attach_image)?.setOnClickListener { attachImage() }
        requireView().findViewById<ImageView>(R.id.btn_attach_to_close)?.setOnClickListener { attachToClose() }
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
        mRefMessages = REF_DATABASE_ROOT.child(NODE_GROUPS).child(group.id).child(NODE_MESSAGES)

        mRecyclerView.apply {
            adapter = mAdapter
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
            layoutManager = mLayoutManager
        }

        mMessagesListener = AppChildEventListener {
            val message = it.getCommonModel()
            val viewMessage = AppViewFactory.getView(message)

            if (mSmoothScrollToPosition) {
                mAdapter.addItemToBottom(viewMessage) {
                    mRecyclerView.smoothScrollToPosition(mAdapter.itemCount)
                }
            } else {
                mAdapter.addItemToTop(viewMessage) {
                    mSwipeRefreshLayout.isRefreshing = false
                }
            }
        }

        mRefMessages.limitToLast(mCountMessages).addChildEventListener(mMessagesListener)

        mRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (mIsScrolling && dy < 0 && mLayoutManager.findFirstVisibleItemPosition() <= 3) {
                    updateData()
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                mIsScrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING
            }
        })

        mSwipeRefreshLayout.setOnRefreshListener { updateData() }
    }

    private fun updateData() {
        mSmoothScrollToPosition = false
        mIsScrolling = false
        mCountMessages += 10
        mRefMessages.removeEventListener(mMessagesListener)
        mRefMessages.limitToLast(mCountMessages).addChildEventListener(mMessagesListener)
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

        // Предзагрузка публичных ключей участников группы (ECDH + Kyber)
        preloadGroupMembersPublicKeys()

        requireView().findViewById<ImageView>(R.id.chat_btn_send_message)?.setOnClickListener {
            mSmoothScrollToPosition = true
            val message = requireView().findViewById<EditText>(R.id.chat_input_message)?.text.toString()
            if (message.isEmpty()) {
                showToast(getString(R.string.enter_a_message))
            } else {
                safeSendGroupMessage(message)
            }
        }
    }

    /** Предзагрузка публичных ключей участников группы */
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
                        ChatEncryptionManager.getOtherUserKyberPublicKey(memberId) { kyberKey ->
                            if (!kyberKey.isNullOrEmpty()) {
                                ChatEncryptionManager.cacheKyberPublicKey(memberId, kyberKey)
                            }
                        }
                    }
                }
            })
    }

    /**
     * Исправленная отправка в группу.
     * Теперь передаём ЧИСТЫЙ текст — шифрование происходит внутри sendMessageToGroup()
     */
    private fun safeSendGroupMessage(message: String) {
        try {
            sendMessageToGroup(message, group.id, TYPE_TEXT) {
                requireView().findViewById<EditText>(R.id.chat_input_message)?.setText("")
            }
        } catch (e: Exception) {
            showToast("Ошибка отправки: ${e.message}")
        }
    }

    private fun initInfoToolbar() {
        val fullname = if (mReceivingUser.fullname.isEmpty()) group.fullname else mReceivingUser.fullname
        mToolbarInfo.findViewById<TextView>(R.id.toolbar_chat_fullname).text = fullname
        mToolbarInfo.findViewById<ImageView>(R.id.toolbar_chat_image).downloadAndSetImage(group.photoUrl)
        mToolbarInfo.findViewById<TextView>(R.id.toolbar_chat_status).text = mReceivingUser.getStateText()
    }

    override fun onPause() {
        super.onPause()
        mToolbarInfo.visibility = View.GONE
        mRefUser.removeEventListener(mListenerInfoToolbar)
        mRefMessages.removeEventListener(mMessagesListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mAppVoiceRecorder.releaseRecorder()
        mAdapter.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        activity?.menuInflater?.inflate(R.menu.single_chat_action_menu, menu)
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
        }
        return true
    }
}