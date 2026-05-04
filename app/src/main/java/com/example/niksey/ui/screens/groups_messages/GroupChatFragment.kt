package com.example.niksey.ui.screens.groups_messages

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val uri = result.uriContent ?: return@registerForActivityResult
                val messageKey = getMessageKey(group.id)
                uploadFileToStorageGroup(uri, messageKey, group.id, TYPE_MESSAGE_IMAGE)
                mSmoothScrollToPosition = true
            } else {
                showToast(APP_ACTIVITY.getString(R.string.error_cropping_image))
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
        mBottomSheetBehavior = BottomSheetBehavior.from(view?.findViewById(R.id.bottom_sheet_choice)!!)
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        mAppVoiceRecorder = AppVoiceRecorder()
        mSwipeRefreshLayout = view?.findViewById(R.id.chat_swipe_refresh)!!
        mLayoutManager = LinearLayoutManager(requireContext())

        view?.findViewById<EditText>(R.id.chat_input_message)?.addTextChangedListener(AppTextWatcher {
            val string = view?.findViewById<EditText>(R.id.chat_input_message)?.text.toString()
            val sendBtn = view?.findViewById<ImageView>(R.id.chat_btn_send_message)
            val attachBtn = view?.findViewById<ImageView>(R.id.chat_btn_attach)
            val voiceBtn = view?.findViewById<ImageView>(R.id.chat_btn_voice)

            if (string.isEmpty() || string == getString(R.string.record)) {
                sendBtn?.visibility = View.GONE
                attachBtn?.visibility = View.VISIBLE
                voiceBtn?.visibility = View.VISIBLE
            } else {
                sendBtn?.visibility = View.VISIBLE
                attachBtn?.visibility = View.GONE
                voiceBtn?.visibility = View.GONE
            }
        })

        view?.findViewById<ImageView>(R.id.chat_btn_attach)?.setOnClickListener { attach() }

        // ==================== AI Quick Reply ====================
        view?.findViewById<ImageView>(R.id.chat_btn_send_message)?.setOnLongClickListener {
            (requireActivity() as com.example.niksey.MainActivity).showAIQuickReplies { reply ->
                view?.findViewById<EditText>(R.id.chat_input_message)?.setText(reply)
            }
            true
        }

        CoroutineScope(Dispatchers.IO).launch {
            view?.findViewById<ImageView>(R.id.chat_btn_voice)?.setOnTouchListener { _, event ->
                if (checkPermission(RECORD_AUDIO)) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            view?.findViewById<EditText>(R.id.chat_input_message)?.setText(getString(R.string.record))
                            view?.findViewById<ImageView>(R.id.chat_btn_voice)?.setColorFilter(
                                ContextCompat.getColor(APP_ACTIVITY, R.color.purple_200)
                            )
                            val messageKey = getMessageKeyGroup(group.id)
                            mAppVoiceRecorder.startRecord(messageKey)
                        }
                        MotionEvent.ACTION_UP -> {
                            view?.findViewById<EditText>(R.id.chat_input_message)?.setText("")
                            view?.findViewById<ImageView>(R.id.chat_btn_voice)?.colorFilter = null
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
    }

    private fun attach() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        view?.findViewById<ImageView>(R.id.btn_attach_file)?.setOnClickListener { attachFile() }
        view?.findViewById<ImageView>(R.id.btn_attach_image)?.setOnClickListener { attachImage() }
        view?.findViewById<ImageView>(R.id.btn_attach_to_close)?.setOnClickListener { attachToclose() }
    }

    private fun attachToclose() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun attachFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
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
        mRecyclerView = view?.findViewById(R.id.chat_recycle_view)!!
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
                if (newState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                    mIsScrolling = true
                }
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

        view?.findViewById<ImageView>(R.id.chat_btn_send_message)?.setOnClickListener {
            mSmoothScrollToPosition = true
            val message = view?.findViewById<EditText>(R.id.chat_input_message)?.text.toString()
            if (message.isEmpty()) {
                showToast(getString(R.string.enter_a_message))
            } else {
                sendMessageToGroup(message, group.id, TYPE_TEXT) {
                    view?.findViewById<EditText>(R.id.chat_input_message)?.setText("")
                }
            }
        }
    }

    private fun initInfoToolbar() {
        val fullname = if (mReceivingUser.fullname.isEmpty()) group.fullname else mReceivingUser.fullname
        mToolbarInfo.findViewById<TextView>(R.id.toolbar_chat_fullname).text = fullname
        mToolbarInfo.findViewById<ImageView>(R.id.toolbar_chat_image).downloadAndSetImage(group.photoUrl)
        mToolbarInfo.findViewById<TextView>(R.id.toolbar_chat_status).text = mReceivingUser.state
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data?.data != null && requestCode == PICK_FILE_REQUEST_CODE) {
            val uri = data.data!!
            val messageKey = getMessageKey(group.id)
            val filename = getFilenameFromUri(uri)
            uploadFileToStorageGroup(uri, messageKey, group.id, TYPE_MESSAGE_FILE, filename)
            mSmoothScrollToPosition = true
        }
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
        val menuRes = if ("$NODE_GROUPS/${group.id}/$NODE_MEMBERS/$CURRENT_UID" == USER_MEMBER)
            R.menu.single_chat_action_menu else R.menu.group_chat_action_menu
        activity?.menuInflater?.inflate(menuRes, menu)
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