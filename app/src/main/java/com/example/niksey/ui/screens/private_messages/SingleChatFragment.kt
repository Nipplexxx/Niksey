@file:Suppress("UNCHECKED_CAST", "DEPRECATION")

package com.example.niksey.ui.screens.private_messages

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.niksey.R
import com.example.niksey.database.clearChat
import com.example.niksey.database.deleteChat
import com.example.niksey.database.getCommonModel
import com.example.niksey.database.getMessageKey
import com.example.niksey.database.getUserModel
import com.example.niksey.database.removeChat
import com.example.niksey.database.uploadFileToStorage
import com.example.niksey.models.CommonModel
import com.example.niksey.models.UserModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.ui.viewmodels.ChatViewModel
import com.example.niksey.utillits.*
import com.google.firebase.database.DatabaseReference
import de.hdodenhof.circleimageview.CircleImageView
import java.io.File

class SingleChatFragment : BaseFragment(R.layout.fragment_chat) {

    private val viewModel: ChatViewModel by viewModels()
    private var contactId: String = ""
    private var contact: CommonModel? = null

    private var mChatInputMessage: EditText? = null
    private var mChatBtnSendMessage: ImageView? = null
    private var mChatBtnAttach: ImageView? = null
    private var mChatBtnVoice: ImageView? = null
    private var mRecyclerView: RecyclerView? = null

    private var mListenerInfoToolbar: AppValueEventListener? = null
    private var mReceivingUser: UserModel? = null
    private var mToolbarInfo: View? = null
    private var mRefUser: DatabaseReference? = null
    private var mAdapter: SingleChatAdapter? = null
    private lateinit var mLayoutManager: LinearLayoutManager
    private lateinit var mAppVoiceRecorder: AppVoiceRecorder

    private lateinit var pickFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var takePhotoLauncher: ActivityResultLauncher<Uri>
    private var pendingPhotoUri: Uri? = null

    private val handler = Handler(Looper.getMainLooper())

    // ==================== REPLY SYSTEM ====================
    private var replyingToMessageId: String? = null
    private var replyingToText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        contactId = arguments?.getString("contact_id") ?: ""
        initLaunchers()
    }

    private fun initLaunchers() {
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                uploadFileToStorage(it, getMessageKey(contactId), contactId, TYPE_MESSAGE_IMAGE) {
                    viewModel.reloadMessages()
                }
            }
        }

        takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && pendingPhotoUri != null) {
                uploadFileToStorage(pendingPhotoUri!!, getMessageKey(contactId), contactId, TYPE_MESSAGE_IMAGE) {
                    viewModel.reloadMessages()
                }
                pendingPhotoUri = null
            } else {
                showToast("Не удалось сделать снимок")
            }
        }

        pickFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                val filename = getFilenameFromUri(uri)
                uploadFileToStorage(uri, getMessageKey(contactId), contactId, TYPE_MESSAGE_FILE, filename) {
                    viewModel.reloadMessages()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (contactId.isBlank()) {
            showToast("Ошибка: контакт не найден")
            replaceFragment(MainListFragment())
            return
        }

        ChatEncryptionManager.currentChatPartnerId = contactId
        ChatEncryptionManager.getOrCreateChatKeyAsync(contactId) { }

        try {
            initViews()
            initListeners()
            initRecyclerView()
            initChat()
        } catch (e: Exception) {
            Log.e("SingleChatFragment", "Критическая ошибка", e)
            showToast("Ошибка открытия чата")
            replaceFragment(MainListFragment())
        }
    }

    private fun initViews() {
        val root = requireView()
        mChatInputMessage = root.findViewById(R.id.chat_input_message)
        mChatBtnSendMessage = root.findViewById(R.id.chat_btn_send_message)
        mChatBtnAttach = root.findViewById(R.id.chat_btn_attach)
        mChatBtnVoice = root.findViewById(R.id.chat_btn_voice)
        mRecyclerView = root.findViewById(R.id.chat_recycle_view)
    }

    private fun initListeners() {
        mChatBtnAttach?.setOnClickListener { attach() }

        mChatBtnSendMessage?.setOnClickListener {
            val message = mChatInputMessage?.text?.toString()?.trim() ?: ""
            if (message.isNotEmpty()) {
                viewModel.sendMessage(message, replyingToMessageId) {
                    mChatInputMessage?.setText("")
                    cancelReply()
                }
            }
        }

        mChatInputMessage?.addTextChangedListener(AppTextWatcher {
            val text = mChatInputMessage?.text?.toString() ?: ""
            val isEmpty = text.isEmpty() || text == getString(R.string.record)
            mChatBtnSendMessage?.visibility = if (isEmpty) View.GONE else View.VISIBLE
            mChatBtnAttach?.visibility = if (isEmpty) View.VISIBLE else View.GONE
            mChatBtnVoice?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        })

        setupVoiceAndVideoRecording()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceAndVideoRecording() {
        mAppVoiceRecorder = AppVoiceRecorder()
        var isRecording = false
        var isVideoMode = false
        var initialY = 0f
        val initialTranslationY = mChatBtnVoice?.translationY ?: 0f

        mChatBtnVoice?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (checkPermission(RECORD_AUDIO)) {
                        isRecording = true
                        isVideoMode = false
                        initialY = event.rawY

                        mChatBtnVoice?.animate()
                            ?.translationY(-120f)
                            ?.scaleX(1.3f)
                            ?.scaleY(1.3f)
                            ?.setDuration(100)
                            ?.start()

                        mChatBtnVoice?.setColorFilter(
                            ContextCompat.getColor(requireContext(), R.color.purple_300)
                        )
                        mChatInputMessage?.setText("Запись голосового...")

                        mAppVoiceRecorder.startRecord(getMessageKey(contactId))
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isRecording) {
                        val deltaY = initialY - event.rawY
                        if (deltaY > 120 && !isVideoMode) {
                            isVideoMode = true
                            mChatInputMessage?.setText("Видео сообщение...")
                            mChatBtnVoice?.setColorFilter(
                                ContextCompat.getColor(requireContext(), R.color.accent_purple)
                            )
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        isRecording = false

                        mChatBtnVoice?.animate()
                            ?.translationY(initialTranslationY)
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.setDuration(120)
                            ?.start()

                        mChatBtnVoice?.colorFilter = null
                        mChatInputMessage?.setText("")

                        if (isVideoMode) {
                            mAppVoiceRecorder.cancelRecord()
                            openCircularVideoRecorder()
                        } else {
                            mAppVoiceRecorder.stopRecord { file, messageKey ->
                                uploadFileToStorage(Uri.fromFile(file), messageKey, contactId, TYPE_MESSAGE_VOICE) {
                                    viewModel.reloadMessages()
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openCircularVideoRecorder() {
        val bottomSheet = VideoRecorderBottomSheet { videoFile ->
            uploadFileToStorage(Uri.fromFile(videoFile), getMessageKey(contactId), contactId, TYPE_MESSAGE_VIDEO) {
                viewModel.reloadMessages()
                showToast("Видео сообщение отправлено")
            }
        }
        bottomSheet.show(parentFragmentManager, "video_recorder")
    }

    private fun initRecyclerView() {
        mLayoutManager = LinearLayoutManager(requireContext())
        mAdapter = SingleChatAdapter()
        mRecyclerView?.apply {
            adapter = mAdapter
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
            layoutManager = mLayoutManager
        }
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            mAdapter?.submitList(messages)
            if (messages.isNotEmpty()) mRecyclerView?.smoothScrollToPosition(messages.size - 1)
        }
    }

    private fun initChat() {
        REF_DATABASE_ROOT.child(NODE_USERS).child(contactId)
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                contact = snapshot.getCommonModel()
                if (contact == null) {
                    showToast("Ошибка: контакт не найден")
                    replaceFragment(MainListFragment())
                    return@AppValueEventListener
                }
                initToolbar()
            })

        viewModel.initChat(contactId, isGroup = false)
        ChatEncryptionManager.getOtherUserPublicKey(contactId) { key ->
            if (!key.isNullOrEmpty()) ChatEncryptionManager.cachePublicKey(contactId, key)
        }
    }

    private fun initToolbar() {
        mToolbarInfo = requireView().findViewById(R.id.toolbar_info)
        if (mToolbarInfo == null) {
            APP_ACTIVITY.supportActionBar?.title = contact?.fullname?.ifEmpty { contact?.username } ?: "Чат"
            return
        }
        mToolbarInfo?.visibility = View.VISIBLE
        mToolbarInfo?.findViewById<TextView>(R.id.toolbar_chat_fullname)?.text = contact?.fullname?.ifEmpty { contact?.username }
        mToolbarInfo?.findViewById<CircleImageView>(R.id.toolbar_chat_image)?.downloadAndSetImage(contact?.photoUrl.orEmpty())

        mListenerInfoToolbar = AppValueEventListener {
            mReceivingUser = it.getUserModel()
            initInfoToolbar()
        }
        mRefUser = REF_DATABASE_ROOT.child(NODE_USERS).child(contactId)
        mRefUser?.addValueEventListener(mListenerInfoToolbar!!)
    }

    private fun initInfoToolbar() {
        val user = mReceivingUser ?: return
        mToolbarInfo?.findViewById<TextView>(R.id.toolbar_chat_fullname)?.text = user.fullname.ifEmpty { contact?.fullname }
        mToolbarInfo?.findViewById<CircleImageView>(R.id.toolbar_chat_image)?.downloadAndSetImage(user.photoUrl)
        val status = when {
            user.state == "online" -> "онлайн"
            user.state == "recording" -> "записывает голосовое..."
            else -> user.getStateText()
        }
        mToolbarInfo?.findViewById<TextView>(R.id.toolbar_chat_status)?.text = status
    }

    // ==================== REPLY SYSTEM ====================

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

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ====================

    private fun attach() {
        val options = arrayOf("Выбрать из галереи", "Сделать снимок")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Прикрепить фото")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickImageLauncher.launch("image/*")
                    1 -> takePhoto()
                }
            }
            .show()
    }

    private fun takePhoto() {
        if (!checkPermission(Manifest.permission.CAMERA)) {
            showToast("Нужно разрешение на камеру")
            return
        }
        try {
            val photoFile = File(requireContext().cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            pendingPhotoUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                photoFile
            )
            takePhotoLauncher.launch(pendingPhotoUri!!)
        } catch (e: Exception) {
            showToast("Не удалось запустить камеру")
        }
    }

    fun deleteMessage(messageId: String, fromUserId: String) {
        val path1 = "$NODE_MESSAGES/$CURRENT_UID/$fromUserId/$messageId"
        val path2 = "$NODE_MESSAGES/$fromUserId/$CURRENT_UID/$messageId"

        REF_DATABASE_ROOT.child(path1).removeValue()
        REF_DATABASE_ROOT.child(path2).removeValue()
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
            mRecyclerView?.smoothScrollToPosition(position)
        } else {
            showToast("Сообщение не найдено")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            mToolbarInfo?.visibility = View.GONE
            APP_ACTIVITY.mToolbar?.title = getString(R.string.app_name)
            mRefUser?.removeEventListener(mListenerInfoToolbar!!)
            mListenerInfoToolbar = null
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) { }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            mAppVoiceRecorder.releaseRecorder()
            mAdapter?.onDestroy()
        } catch (e: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        activity?.menuInflater?.inflate(R.menu.single_chat_action_menu, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear_chat -> clearChat(contactId) { replaceFragment(MainListFragment()) }
            R.id.menu_remove_chat -> removeChat(contactId) { replaceFragment(MainListFragment()) }
            R.id.menu_delete_chat -> deleteChat(contactId) { replaceFragment(MainListFragment()) }
        }
        return true
    }

    companion object {
        fun newInstance(contactId: String): SingleChatFragment {
            return SingleChatFragment().apply {
                arguments = Bundle().apply { putString("contact_id", contactId) }
            }
        }
    }
}