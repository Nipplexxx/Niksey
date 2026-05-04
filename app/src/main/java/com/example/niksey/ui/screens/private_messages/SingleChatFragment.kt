@file:Suppress("UNCHECKED_CAST", "DEPRECATION")
package com.example.niksey.ui.screens.private_messages

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import de.hdodenhof.circleimageview.CircleImageView
import com.example.niksey.utillits.ChatEncryptionManager

class SingleChatFragment(private var contact: CommonModel) : BaseFragment(R.layout.fragment_chat) {

    // ====================== View references ======================
    private lateinit var mChatInputMessage: EditText
    private lateinit var mChatBtnSendMessage: ImageView
    private lateinit var mChatBtnAttach: ImageView
    private lateinit var mChatBtnVoice: ImageView

    private lateinit var mBtnAttachFile: View
    private lateinit var mBtnAttachImage: View
    private lateinit var mBtnAttachClose: View

    // ====================== Firebase & listeners ======================
    private lateinit var mListenerInfoToolbar: AppValueEventListener
    private lateinit var mReceivingUser: UserModel
    private var mToolbarInfo: View? = null
    private lateinit var mRefUser: DatabaseReference
    private lateinit var mRefMessages: DatabaseReference
    private lateinit var mAdapter: SingleChatAdapter
    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mMessagesListener: AppChildEventListener

    // ====================== State ======================
    private var mCountMessages = 10
    private var mIsScrolling = false
    private var mSmoothScrollToPosition = true

    // ====================== UI components ======================
    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout
    private lateinit var mLayoutManager: LinearLayoutManager
    private lateinit var mAppVoiceRecorder: AppVoiceRecorder
    private lateinit var mBottomSheetBehavior: BottomSheetBehavior<*>

    // ====================== Activity Result Launchers ======================
    private lateinit var cropImageLauncher: ActivityResultLauncher<CropImageContractOptions>
    private lateinit var pickFileLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // === Crop Image ===
        cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val uri = result.uriContent ?: return@registerForActivityResult
                val messageKey = getMessageKey(contact.id)
                uploadFileToStorage(uri, messageKey, contact.id, TYPE_MESSAGE_IMAGE)
                mSmoothScrollToPosition = true
            } else {
                showToast(getString(R.string.error_cropping_image))
            }
        }

        // === Pick File ===
        pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                val messageKey = getMessageKey(contact.id)
                val filename = getFilenameFromUri(uri)
                uploadFileToStorage(uri, messageKey, contact.id, TYPE_MESSAGE_FILE, filename)
                mSmoothScrollToPosition = true
            }
        }
    }

    private fun initViews() {
        val root = requireView()
        mChatInputMessage = root.findViewById(R.id.chat_input_message)
        mChatBtnSendMessage = root.findViewById(R.id.chat_btn_send_message)
        mChatBtnAttach = root.findViewById(R.id.chat_btn_attach)
        mChatBtnVoice = root.findViewById(R.id.chat_btn_voice)

        mBtnAttachFile = root.findViewById(R.id.btn_attach_file)
        mBtnAttachImage = root.findViewById(R.id.btn_attach_image)
        mBtnAttachClose = root.findViewById(R.id.btn_attach_to_close)

        mSwipeRefreshLayout = root.findViewById(R.id.chat_swipe_refresh)
        mRecyclerView = root.findViewById(R.id.chat_recycle_view)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFields() {
        setHasOptionsMenu(true)

        mBottomSheetBehavior = BottomSheetBehavior.from(requireView().findViewById(R.id.bottom_sheet_choice))
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        mAppVoiceRecorder = AppVoiceRecorder()
        mLayoutManager = LinearLayoutManager(requireContext())

        // TextWatcher
        mChatInputMessage.addTextChangedListener(AppTextWatcher {
            val text = mChatInputMessage.text.toString()
            val isEmptyOrRecording = text.isEmpty() || text == getString(R.string.record)

            mChatBtnSendMessage.visibility = if (isEmptyOrRecording) View.GONE else View.VISIBLE
            mChatBtnAttach.visibility = if (isEmptyOrRecording) View.VISIBLE else View.GONE
            mChatBtnVoice.visibility = if (isEmptyOrRecording) View.VISIBLE else View.GONE
        })

        mChatBtnAttach.setOnClickListener { attach() }

        // === Отправка сообщения с обработкой ошибок ===
        mChatBtnSendMessage.setOnClickListener {
            mSmoothScrollToPosition = true
            val message = mChatInputMessage.text.toString().trim()
            if (message.isEmpty()) {
                showToast(getString(R.string.enter_a_message))
                return@setOnClickListener
            }

            safeSendMessage(message)
        }

        // Голосовое сообщение
        mChatBtnVoice.setOnTouchListener { _, event ->
            if (checkPermission(RECORD_AUDIO)) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        mChatInputMessage.setText(getString(R.string.record))
                        mChatBtnVoice.setColorFilter(ContextCompat.getColor(requireContext(), R.color.purple_200))
                        val messageKey = getMessageKey(contact.id)
                        mAppVoiceRecorder.startRecord(messageKey)
                    }
                    MotionEvent.ACTION_UP -> {
                        mChatInputMessage.setText("")
                        mChatBtnVoice.colorFilter = null
                        mAppVoiceRecorder.stopRecord { file, messageKey ->
                            uploadFileToStorage(Uri.fromFile(file), messageKey, contact.id, TYPE_MESSAGE_VOICE)
                            mSmoothScrollToPosition = true
                        }
                    }
                }
            }
            true
        }
    }

    // === Безопасная отправка сообщения с обработкой ошибок ===
    private fun safeSendMessage(message: String) {
        try {
            sendMessage(message, contact.id, TYPE_TEXT) {
                saveToMainList(contact.id, TYPE_CHAT)
                mChatInputMessage.setText("")
            }
        } catch (e: Exception) {
            showToast("Ошибка отправки: ${e.message}")
        }
    }

    private fun attach() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        mBtnAttachFile.setOnClickListener { attachFile() }
        mBtnAttachImage.setOnClickListener { attachImage() }
        mBtnAttachClose.setOnClickListener { attachToClose() }
    }

    private fun attachToClose() {
        mBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun attachFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        pickFileLauncher.launch(intent)
    }

    private fun attachImage() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCropper()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1001)
        }
    }

    private fun launchCropper() {
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCropper()
        } else {
            showToast("Нужно разрешение на камеру")
        }
    }

    private fun initRecycleView() {
        mAdapter = SingleChatAdapter()
        mRefMessages = REF_DATABASE_ROOT.child(NODE_MESSAGES).child(CURRENT_UID).child(contact.id)

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

    override fun onResume() {
        super.onResume()
        initViews()
        initFields()

        ChatEncryptionManager.currentChatPartnerId = contact.id

        // Временно упрощённая загрузка ключа (прямо из Firebase)
        REF_DATABASE_ROOT.child("users/${contact.id}/publicKey")
            .get()
            .addOnSuccessListener { snapshot ->
                val key = snapshot.value as? String
                if (!key.isNullOrEmpty()) {
                    ChatEncryptionManager.cachePublicKey(contact.id, key)
                }
            }

        initToolbar()
        initRecycleView()
    }

    private fun initToolbar() {
        val toolbar = APP_ACTIVITY.mToolbar ?: return
        mToolbarInfo = toolbar.findViewById(R.id.toolbar_info)

        if (mToolbarInfo == null) {
            APP_ACTIVITY.supportActionBar?.title = contact.fullname.ifEmpty { contact.username }
            return
        }

        mToolbarInfo?.visibility = View.VISIBLE

        // Сразу показываем данные
        val fullname = contact.fullname.ifEmpty { contact.username }
        mToolbarInfo?.findViewById<TextView>(R.id.toolbar_chat_fullname)?.text = fullname
        mToolbarInfo?.findViewById<CircleImageView>(R.id.toolbar_chat_image)
            ?.downloadAndSetImage(contact.photoUrl)

        // Подписка на обновления
        mListenerInfoToolbar = AppValueEventListener {
            mReceivingUser = it.getUserModel()

            if (mReceivingUser.publicKey.isNotEmpty()) {
                ChatEncryptionManager.cachePublicKey(contact.id, mReceivingUser.publicKey)
            }

            initInfoToolbar()
        }

        mRefUser = REF_DATABASE_ROOT.child(NODE_USERS).child(contact.id)
        mRefUser.addValueEventListener(mListenerInfoToolbar)
    }

    private fun initInfoToolbar() {
        mToolbarInfo?.let { toolbar ->
            val fullname = if (mReceivingUser.fullname.isEmpty()) contact.fullname else mReceivingUser.fullname

            toolbar.findViewById<TextView>(R.id.toolbar_chat_fullname)?.text = fullname
            toolbar.findViewById<CircleImageView>(R.id.toolbar_chat_image)
                ?.downloadAndSetImage(mReceivingUser.photoUrl)
            toolbar.findViewById<TextView>(R.id.toolbar_chat_status)?.text = mReceivingUser.getStateText()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            mToolbarInfo?.visibility = View.GONE
            APP_ACTIVITY.mToolbar?.title = getString(R.string.app_name)

            if (::mRefUser.isInitialized && ::mListenerInfoToolbar.isInitialized) {
                mRefUser.removeEventListener(mListenerInfoToolbar)
            }

            if (::mRefMessages.isInitialized && ::mMessagesListener.isInitialized) {
                mRefMessages.removeEventListener(mMessagesListener)
            }

            ChatEncryptionManager.currentChatPartnerId = null

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            mAppVoiceRecorder.releaseRecorder()
            mAdapter.onDestroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        activity?.menuInflater?.inflate(R.menu.single_chat_action_menu, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_chat -> clearChat(contact.id) {
                showToast(getString(R.string.chat_cleared))
                replaceFragment(MainListFragment())
            }
            R.id.menu_remove_chat -> removeChat(contact.id) {
                showToast(getString(R.string.chat_remove))
                replaceFragment(MainListFragment())
            }
            R.id.menu_delete_chat -> deleteChat(contact.id) {
                showToast(getString(R.string.chat_deleted))
                replaceFragment(MainListFragment())
            }
        }
        return true
    }

    private fun showAIQuickRepliesLocal() {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_ai_replies, null)
        bottomSheet.setContentView(view)

        val reply1 = view.findViewById<TextView>(R.id.reply_1)
        val reply2 = view.findViewById<TextView>(R.id.reply_2)
        val reply3 = view.findViewById<TextView>(R.id.reply_3)
        val reply4 = view.findViewById<TextView>(R.id.reply_4)

        val replies = listOf(
            getString(R.string.ai_reply_thanks),
            getString(R.string.ai_reply_ok),
            getString(R.string.ai_reply_later),
            getString(R.string.ai_reply_call_me)
        )

        reply1.setOnClickListener { mChatInputMessage.setText(replies[0]); bottomSheet.dismiss() }
        reply2.setOnClickListener { mChatInputMessage.setText(replies[1]); bottomSheet.dismiss() }
        reply3.setOnClickListener { mChatInputMessage.setText(replies[2]); bottomSheet.dismiss() }
        reply4.setOnClickListener { mChatInputMessage.setText(replies[3]); bottomSheet.dismiss() }

        bottomSheet.show()
    }
}