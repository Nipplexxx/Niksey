@file:Suppress("UNCHECKED_CAST", "DEPRECATION")
package com.example.niksey.ui.screens.private_messages

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
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
import com.example.niksey.database.clearChat
import com.example.niksey.database.deleteChat
import com.example.niksey.database.getMessageKey
import com.example.niksey.database.getUserModel
import com.example.niksey.database.removeChat
import com.example.niksey.database.uploadFileToStorage
import com.example.niksey.models.CommonModel
import com.example.niksey.models.UserModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.ui.viewmodels.ChatViewModel
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.AppTextWatcher
import com.example.niksey.utillits.AppValueEventListener
import com.example.niksey.utillits.AppVoiceRecorder
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.RECORD_AUDIO
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.TYPE_MESSAGE_FILE
import com.example.niksey.utillits.TYPE_MESSAGE_IMAGE
import com.example.niksey.utillits.TYPE_MESSAGE_VOICE
import com.example.niksey.utillits.checkPermission
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.getFilenameFromUri
import com.example.niksey.utillits.replaceFragment
import com.example.niksey.utillits.showToast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.database.DatabaseReference
import de.hdodenhof.circleimageview.CircleImageView

class SingleChatFragment(private var contact: CommonModel) : BaseFragment(R.layout.fragment_chat) {

    private val viewModel: ChatViewModel by viewModels()

    private lateinit var mChatInputMessage: EditText
    private lateinit var mChatBtnSendMessage: ImageView
    private lateinit var mChatBtnAttach: ImageView
    private lateinit var mChatBtnVoice: ImageView

    private lateinit var mBtnAttachFile: View
    private lateinit var mBtnAttachImage: View
    private lateinit var mBtnAttachClose: View

    private lateinit var mListenerInfoToolbar: AppValueEventListener
    private lateinit var mReceivingUser: UserModel
    private var mToolbarInfo: View? = null
    private lateinit var mRefUser: DatabaseReference
    private lateinit var mAdapter: SingleChatAdapter
    private lateinit var mRecyclerView: RecyclerView
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
                val messageKey = getMessageKey(contact.id)
                uploadFileToStorage(uri, messageKey, contact.id, TYPE_MESSAGE_IMAGE) {
                    viewModel.reloadMessages()
                }
            } else {
                showToast(getString(R.string.error_cropping_image))
            }
        }

        pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                val messageKey = getMessageKey(contact.id)
                val filename = getFilenameFromUri(uri)
                uploadFileToStorage(uri, messageKey, contact.id, TYPE_MESSAGE_FILE, filename) {
                    viewModel.reloadMessages()
                }
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

        mChatInputMessage.addTextChangedListener(AppTextWatcher {
            val text = mChatInputMessage.text.toString()
            val isEmptyOrRecording = text.isEmpty() || text == getString(R.string.record)

            mChatBtnSendMessage.visibility = if (isEmptyOrRecording) View.GONE else View.VISIBLE
            mChatBtnAttach.visibility = if (isEmptyOrRecording) View.VISIBLE else View.GONE
            mChatBtnVoice.visibility = if (isEmptyOrRecording) View.VISIBLE else View.GONE
        })

        mChatBtnAttach.setOnClickListener { attach() }

        mChatBtnSendMessage.setOnClickListener {
            val message = mChatInputMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                viewModel.sendMessage(message) {
                    mChatInputMessage.setText("")
                }
            }
        }

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
                            uploadFileToStorage(Uri.fromFile(file), messageKey, contact.id, TYPE_MESSAGE_VOICE) {
                                viewModel.reloadMessages()
                            }
                        }
                    }
                }
            }
            true
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

    @Deprecated("Deprecated in Java")
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
        mRecyclerView.apply {
            adapter = mAdapter
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
            layoutManager = mLayoutManager
        }

        // Наблюдаем за сообщениями из ViewModel
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            mAdapter.submitList(messages)
            if (messages.isNotEmpty()) {
                mRecyclerView.smoothScrollToPosition(messages.size - 1)
            }
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
    }

    override fun onResume() {
        super.onResume()
        initViews()
        initFields()

        // Инициализируем ViewModel
        viewModel.initChat(contact.id, isGroup = false)

        // Предзагружаем публичные ключи
        ChatEncryptionManager.getOtherUserPublicKey(contact.id) { publicKey ->
            if (!publicKey.isNullOrEmpty()) {
                ChatEncryptionManager.cachePublicKey(contact.id, publicKey)
            }
        }

        ChatEncryptionManager.getOtherUserKyberPublicKey(contact.id) { kyberKey ->
            if (!kyberKey.isNullOrEmpty()) {
                ChatEncryptionManager.cacheKyberPublicKey(contact.id, kyberKey)
            }
        }

        initToolbar()
        initRecycleView()
    }

    private fun initToolbar() {
        val toolbar = APP_ACTIVITY.mToolbar
        mToolbarInfo = toolbar.findViewById(R.id.toolbar_info)

        if (mToolbarInfo == null) {
            APP_ACTIVITY.supportActionBar?.title = contact.fullname.ifEmpty { contact.username }
            return
        }

        mToolbarInfo?.visibility = View.VISIBLE

        val fullname = contact.fullname.ifEmpty { contact.username }
        mToolbarInfo?.findViewById<TextView>(R.id.toolbar_chat_fullname)?.text = fullname
        mToolbarInfo?.findViewById<CircleImageView>(R.id.toolbar_chat_image)
            ?.downloadAndSetImage(contact.photoUrl)

        mListenerInfoToolbar = AppValueEventListener {
            mReceivingUser = it.getUserModel()

            if (mReceivingUser.publicKey.isNotEmpty()) {
                ChatEncryptionManager.cachePublicKey(contact.id, mReceivingUser.publicKey)
            }
            if (mReceivingUser.kyberPublicKey.isNotEmpty()) {
                ChatEncryptionManager.cacheKyberPublicKey(contact.id, mReceivingUser.kyberPublicKey)
            }

            initInfoToolbar()
        }

        mRefUser = REF_DATABASE_ROOT.child(NODE_USERS).child(contact.id)
        mRefUser.addValueEventListener(mListenerInfoToolbar)
    }

    private fun initInfoToolbar() {
        mToolbarInfo?.let { toolbar ->
            val fullname = mReceivingUser.fullname.ifEmpty { contact.fullname }

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
            APP_ACTIVITY.mToolbar.title = getString(R.string.app_name)

            if (::mRefUser.isInitialized && ::mListenerInfoToolbar.isInitialized) {
                mRefUser.removeEventListener(mListenerInfoToolbar)
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
}