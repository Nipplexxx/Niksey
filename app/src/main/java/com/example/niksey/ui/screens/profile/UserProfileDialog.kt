package com.example.niksey.ui.screens.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.example.niksey.R
import com.example.niksey.database.getUserModel
import com.example.niksey.models.UserModel
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.AppValueEventListener
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.showToast
import de.hdodenhof.circleimageview.CircleImageView

/**
 * Профиль собеседника: стеклянная карточка по центру поверх чата.
 *
 * Чат остаётся видимым под затемнением. Закрыть можно нажатием вне карточки, системной
 * кнопкой «назад» или протягиванием карточки вниз.
 */
class UserProfileDialog : DialogFragment() {

    private var userId: String = ""
    private var fullId: String = ""

    override fun getTheme(): Int = R.style.NikseyProfileDialog

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            // Лист прижат к низу экрана на всю ширину; высота — по содержимому
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(DIM_AMOUNT)
            setWindowAnimations(R.style.NikseyProfileSheetAnimation)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.sheet_user_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userId = arguments?.getString(ARG_USER_ID).orEmpty()
        fullId = userId

        view.findViewById<View>(R.id.profile_copy_id).setOnClickListener { copyId() }
        attachSwipeToDismiss(view.findViewById(R.id.profile_card))

        // «Написать»: закрываем карточку и открываем чат (как в Telegram)
        view.findViewById<View>(R.id.profile_send_message)?.setOnClickListener {
            if (userId.isBlank()) return@setOnClickListener
            dismiss()
            com.example.niksey.utillits.replaceFragment(
                com.example.niksey.ui.screens.private_messages.SingleChatFragment.newInstance(userId)
            )
        }

        if (userId.isBlank()) return
        REF_DATABASE_ROOT.child(NODE_USERS).child(userId)
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                if (isAdded && view != null) bind(snapshot.getUserModel())
            })
    }

    /** Протягивание карточки вниз закрывает диалог; короткий сдвиг возвращает карточку на место. */
    private fun attachSwipeToDismiss(card: View) {
        val slop = ViewConfiguration.get(card.context).scaledTouchSlop
        val dismissDistance = card.resources.displayMetrics.density * DISMISS_DISTANCE_DP
        var downY = 0f
        var dragging = false

        card.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (!dragging && dy > slop) dragging = true
                    if (dragging) v.translationY = dy.coerceAtLeast(0f)
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && v.translationY > dismissDistance) {
                        dismissAllowingStateLoss()
                    } else {
                        v.animate().translationY(0f).setDuration(SNAP_BACK_MS).start()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun bind(user: UserModel) {
        val v = view ?: return
        fullId = user.id.ifEmpty { userId }

        v.findViewById<CircleImageView>(R.id.profile_photo).downloadAndSetImage(user.photoUrl)
        v.findViewById<TextView>(R.id.profile_name).text =
            user.fullname.ifEmpty { user.username.ifEmpty { getString(R.string.profile_not_set) } }
        // Нет username — строку скрываем, иначе в карточке остаётся пустой разрыв
        val usernameView = v.findViewById<TextView>(R.id.profile_username)
        if (user.username.isNotBlank()) {
            usernameView.visibility = View.VISIBLE
            usernameView.text = "@${user.username}"
        } else {
            usernameView.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.profile_id).text =
            if (fullId.length > ID_PREVIEW_LENGTH) fullId.take(ID_PREVIEW_LENGTH) + "…" else fullId

        val online = user.state == "online"
        val statusText = when {
            online -> "онлайн"
            user.state == "recording" -> "записывает голосовое..."
            else -> user.getStateText()
        }
        v.findViewById<TextView>(R.id.profile_status).text = statusText
        (v.findViewById<View>(R.id.profile_status_dot).background as? GradientDrawable)?.setColor(
            ContextCompat.getColor(requireContext(), if (online) R.color.accent_cyan else R.color.text_hint)
        )

        v.findViewById<TextView>(R.id.profile_bio).text =
            user.bio.ifBlank { getString(R.string.profile_not_set) }
        v.findViewById<TextView>(R.id.profile_phone).text =
            user.phone.ifBlank { getString(R.string.profile_not_set) }
    }

    private fun copyId() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ID", fullId))
        showToast(APP_ACTIVITY.getString(R.string.profile_copy_id))
    }

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val ID_PREVIEW_LENGTH = 14
        private const val DIM_AMOUNT = 0.6f
        private const val DISMISS_DISTANCE_DP = 110f
        private const val SNAP_BACK_MS = 160L

        fun newInstance(userId: String): UserProfileDialog {
            return UserProfileDialog().apply {
                arguments = Bundle().apply { putString(ARG_USER_ID, userId) }
            }
        }
    }
}
