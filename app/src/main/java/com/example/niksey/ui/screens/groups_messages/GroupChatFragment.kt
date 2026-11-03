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
import androidx.appcompat.app.AlertDialog
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
import com.example.niksey.ui.objects.bindIdentityBanner
import com.example.niksey.ui.objects.setStatusDotColor
import com.example.niksey.ui.objects.showChatMenu
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.ui.viewmodels.ChatViewModel
import com.example.niksey.utillits.*
import com.example.niksey.utillits.signal.SecureMessaging
import com.example.niksey.utillits.signal.SignalManager
import com.google.firebase.database.DatabaseReference

/**
 * Групповой чат.
 *
 * Жизненный цикл:
 *  • onViewCreated — все вью и объекты создаются здесь, один раз на вью; здесь же подписки (observers).
 *  • onResume — только то, что зависит от сессии (ключи, шапка, участники). Всё, что создаётся в onResume,
 *    должно уметь пересоздаваться без дублирования.
 *  • onPause — снимаем слушатели, которые добавили в onResume.
 *  Поля, которые могут быть не готовы, объявлены как nullable (без lateinit): обращение до готовности не падает.
 */
class GroupChatFragment(private val group: CommonModel) : BaseFragment(R.layout.fragment_chat) {

    private val viewModel: ChatViewModel by viewModels()

    // Создаются в onViewCreated
    private var mToolbarInfo: View? = null
    private var mSwipeRefreshLayout: SwipeRefreshLayout? = null
    private var mRecyclerView: RecyclerView? = null
    private var mAdapter: GroupChatAdapter? = null
    private var mLayoutManager: LinearLayoutManager? = null
    private var mAppVoiceRecorder: AppVoiceRecorder? = null

    // Создаются в onCreate (регистрация launcher'ов обязана быть до onStart)
    private var cropImageLauncher: ActivityResultLauncher<CropImageContractOptions>? = null
    private var pickFileLauncher: ActivityResultLauncher<Intent>? = null

    // Слушатель шапки: добавляется в onResume, снимается в onPause
    private var mRefUser: DatabaseReference? = null
    private var mListenerInfoToolbar: AppValueEventListener? = null
    private var mReceivingUser: UserModel? = null

    private var participantsCount = 0

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Вью и объекты, от которых зависит всё остальное
        mToolbarInfo = view.findViewById(R.id.toolbar_info)
        mSwipeRefreshLayout = view.findViewById(R.id.chat_swipe_refresh)
        mLayoutManager = LinearLayoutManager(requireContext())
        mAdapter = GroupChatAdapter()
        mAppVoiceRecorder = AppVoiceRecorder()
        mRecyclerView = view.findViewById<RecyclerView>(R.id.chat_recycle_view).apply {
            adapter = mAdapter
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
            layoutManager = mLayoutManager
        }

        // 2. Обработчики ввода и кнопок — один раз
        initInputFields(view)

        // 3. Подписки на данные — один раз на вью (viewLifecycleOwner)
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            mAdapter?.submitList(messages)
            if (messages.isNotEmpty()) mRecyclerView?.smoothScrollToPosition(messages.size - 1)
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            mSwipeRefreshLayout?.isRefreshing = isLoading
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                showToast(it)
                viewModel.clearError()
            }
        }
        mSwipeRefreshLayout?.setOnRefreshListener { viewModel.loadMoreMessages() }

        // 4. Загрузка сообщений — один раз на вью
        viewModel.initChat(group.id, isGroup = true)
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.mAppDrawer.setToolbarVisible(false)
        SecureMessaging.onDecryptedUpdate = { mAdapter?.notifyDataSetChanged() }

        val conversationId = ChatEncryptionManager.groupConversationId(group.id)
        ChatEncryptionManager.currentConversationId = conversationId
        ChatEncryptionManager.getKeyAsync(conversationId) { key ->
            if (key != null) APP_ACTIVITY.runOnUiThread { mAdapter?.notifyDataSetChanged() }
        }

        // Забираем sender keys участников, адресованные мне; затем перерисовываем сообщения
        SignalManager.syncGroupKeys(group.id) {
            activity?.runOnUiThread { mAdapter?.notifyDataSetChanged() }
        }
        SignalManager.fetchMembers(group.id) { members ->
            bindIdentityBanner(members.orEmpty().filter { it != CURRENT_UID })
        }

        initToolbar()
        loadParticipantsCount()
    }

    override fun onPause() {
        super.onPause()
        SecureMessaging.onDecryptedUpdate = null
        mListenerInfoToolbar?.let { mRefUser?.removeEventListener(it) }
        mRefUser = null
        mListenerInfoToolbar = null
        mToolbarInfo?.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mAppVoiceRecorder?.releaseRecorder()
        mAdapter?.onDestroy()
        mRecyclerView = null
        mAdapter = null
        mLayoutManager = null
        mSwipeRefreshLayout = null
        mToolbarInfo = null
    }

    // ==================== ВВОД ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun initInputFields(view: View) {
        view.findViewById<EditText>(R.id.chat_input_message)
            .addTextChangedListener(AppTextWatcher { text ->
                val sendBtn = view.findViewById<ImageView>(R.id.chat_btn_send_message)
                val attachBtn = view.findViewById<ImageView>(R.id.chat_btn_attach)
                val voiceBtn = view.findViewById<ImageView>(R.id.chat_btn_voice)

                val textStr = text?.toString() ?: ""
                val isEmpty = textStr.isEmpty() || textStr == getString(R.string.record)

                sendBtn?.visibility = if (isEmpty) View.GONE else View.VISIBLE
                attachBtn?.visibility = if (isEmpty) View.VISIBLE else View.GONE
                voiceBtn?.visibility = if (isEmpty) View.VISIBLE else View.GONE
            })

        view.findViewById<ImageView>(R.id.chat_btn_attach)?.setOnClickListener { attach() }

        view.findViewById<ImageView>(R.id.chat_btn_send_message)?.setOnClickListener {
            val message = view.findViewById<EditText>(R.id.chat_input_message)?.text?.toString()?.trim() ?: ""
            if (message.isNotEmpty()) {
                viewModel.sendMessage(message, replyingToMessageId) {
                    view.findViewById<EditText>(R.id.chat_input_message)?.setText("")
                    cancelReply()
                }
            }
        }

        view.findViewById<ImageView>(R.id.chat_btn_voice)?.setOnTouchListener { _, event ->
            if (checkPermission(RECORD_AUDIO)) {
                val recorder = mAppVoiceRecorder
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.findViewById<EditText>(R.id.chat_input_message)?.setText(getString(R.string.record))
                        view.findViewById<ImageView>(R.id.chat_btn_voice)?.setColorFilter(
                            ContextCompat.getColor(APP_ACTIVITY, R.color.purple_300)
                        )
                        recorder?.startRecord(getMessageKeyGroup(group.id))
                    }
                    MotionEvent.ACTION_UP -> {
                        view.findViewById<EditText>(R.id.chat_input_message)?.setText("")
                        view.findViewById<ImageView>(R.id.chat_btn_voice)?.colorFilter = null
                        recorder?.stopRecord { file, messageKey ->
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

    // ==================== ШАПКА ====================

    private fun initToolbar() {
        val header = mToolbarInfo ?: return
        header.visibility = View.VISIBLE
        header.findViewById<ImageView>(R.id.chat_btn_back)?.setOnClickListener { parentFragmentManager.popBackStack() }
        header.findViewById<ImageView>(R.id.chat_btn_more)?.setOnClickListener { showChatMenu(it, R.menu.single_chat_action_menu) }

        // Слушатель добавляем только если его ещё нет (onResume может вызываться многократно)
        if (mListenerInfoToolbar == null) {
            val listener = AppValueEventListener { snapshot ->
                mReceivingUser = snapshot.getUserModel()
                initInfoToolbar()
            }
            val ref = REF_DATABASE_ROOT.child(NODE_USERS).child(group.id)
            ref.addValueEventListener(listener)
            mRefUser = ref
            mListenerInfoToolbar = listener
        }

        preloadGroupMembersPublicKeys()
    }

    private fun preloadGroupMembersPublicKeys() {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_MEMBERS")
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                snapshot.children.forEach { member ->
                    val memberId = member.key ?: return@forEach
                    if (memberId != CURRENT_UID) {
                        ChatEncryptionManager.getOtherUserPublicKey(memberId) { publicKey ->
                            if (!publicKey.isNullOrEmpty()) ChatEncryptionManager.cachePublicKey(memberId, publicKey)
                        }
                    }
                }
            })
    }

    private fun initInfoToolbar() {
        val header = mToolbarInfo ?: return
        val user = mReceivingUser ?: return
        header.findViewById<TextView>(R.id.toolbar_chat_fullname)?.text = user.fullname.ifEmpty { group.fullname }
        header.findViewById<ImageView>(R.id.toolbar_chat_image)?.downloadAndSetImage(group.photoUrl)
        header.findViewById<TextView>(R.id.toolbar_chat_status)?.text = user.getStateText()
        header.findViewById<View>(R.id.chat_status_dot)?.let { setStatusDotColor(it, user.state == "online") }
    }

    private fun loadParticipantsCount() {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_PARTICIPANTS")
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                participantsCount = snapshot.childrenCount.toInt()
                APP_ACTIVITY.title = "${group.fullname} ($participantsCount)"
            })
    }

    // ==================== REPLY SYSTEM ====================

    fun startReply(messageId: String, messageText: String, fromUser: String) {
        replyingToMessageId = messageId
        replyingToText = messageText.take(70) + if (messageText.length > 70) "..." else ""
        showReplyPreview(fromUser)
    }

    private fun showReplyPreview(fromUser: String) {
        val replyPreview = view?.findViewById<View>(R.id.reply_preview) ?: return
        replyPreview.findViewById<TextView>(R.id.reply_text)?.text = "↩️ $fromUser: $replyingToText"
        replyPreview.visibility = View.VISIBLE
        replyPreview.findViewById<ImageView>(R.id.reply_close)?.setOnClickListener { cancelReply() }
    }

    private fun cancelReply() {
        replyingToMessageId = null
        replyingToText = null
        view?.findViewById<View>(R.id.reply_preview)?.visibility = View.GONE
    }

    // ==================== ПУБЛИЧНЫЕ МЕТОДЫ ДЛЯ ХОЛДЕРОВ ====================

    fun deleteMessage(messageId: String, fromUserId: String) {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_MESSAGES/$messageId").removeValue()
            .addOnSuccessListener {
                showToast("Сообщение удалено")
                viewModel.reloadMessages()
            }
            .addOnFailureListener { showToast("Не удалось удалить сообщение") }
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

    // ==================== ВЛОЖЕНИЯ ====================

    /** Вложение: фото или файл (как в личном чате). */
    private fun attach() {
        AlertDialog.Builder(requireContext())
            .setTitle("Прикрепить")
            .setItems(arrayOf("Фото", "Файл")) { _, which ->
                when (which) {
                    0 -> attachImage()
                    1 -> attachFile()
                }
            }
            .show()
    }

    private fun attachFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        pickFileLauncher?.launch(intent)
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
        cropImageLauncher?.launch(options)
    }

    // ==================== МЕНЮ ====================

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
