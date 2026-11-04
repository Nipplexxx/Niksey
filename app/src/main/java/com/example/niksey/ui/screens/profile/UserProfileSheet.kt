package com.example.niksey.ui.screens.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.niksey.R
import com.example.niksey.utillits.NODE_USERS
import com.example.niksey.utillits.REF_DATABASE_ROOT
import com.example.niksey.database.getUserModel
import com.example.niksey.models.UserModel
import com.example.niksey.utillits.AppValueEventListener
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.replaceFragment
import com.example.niksey.utillits.showToast
import com.example.niksey.ui.screens.private_messages.SingleChatFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import de.hdodenhof.circleimageview.CircleImageView

/**
 * Профиль собеседника: открывается нажатием на шапку чата.
 * Показывает аватар, имя, @username, ID, статус и доступные сведения.
 */
class UserProfileSheet : BottomSheetDialogFragment() {

    private var userId: String = ""
    private var fullId: String = ""

    override fun getTheme(): Int = R.style.NikseyBottomSheetDialog

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.sheet_user_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userId = arguments?.getString(ARG_USER_ID).orEmpty()
        fullId = userId

        view.findViewById<View>(R.id.profile_copy_id).setOnClickListener { copyId() }

        // «Написать»: закрываем лист и открываем чат (как в Telegram)
        view.findViewById<View>(R.id.profile_send_message)?.setOnClickListener {
            if (userId.isBlank()) return@setOnClickListener
            dismiss()
            replaceFragment(SingleChatFragment.newInstance(userId))
        }

        if (userId.isBlank()) return
        REF_DATABASE_ROOT.child(NODE_USERS).child(userId)
            .addListenerForSingleValueEvent(AppValueEventListener { snapshot ->
                if (isAdded && view != null) bind(snapshot.getUserModel())
            })
    }

    private fun bind(user: UserModel) {
        val v = view ?: return
        fullId = user.id.ifEmpty { userId }

        v.findViewById<CircleImageView>(R.id.profile_photo).downloadAndSetImage(user.photoUrl)
        v.findViewById<TextView>(R.id.profile_name).text =
            user.fullname.ifEmpty { user.username.ifEmpty { getString(R.string.profile_not_set) } }
        // Если username нет — строку скрываем, иначе в шапке остаётся пустой разрыв
        val usernameView = v.findViewById<TextView>(R.id.profile_username)
        if (user.username.isNotBlank()) {
            usernameView.visibility = View.VISIBLE
            usernameView.text = "@${user.username}"
        } else {
            usernameView.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.profile_id).text = "ID · ${fullId.take(14)}"

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
        showToast(getString(R.string.profile_copy_id))
    }

    companion object {
        private const val ARG_USER_ID = "user_id"

        fun newInstance(userId: String): UserProfileSheet {
            return UserProfileSheet().apply {
                arguments = Bundle().apply { putString(ARG_USER_ID, userId) }
            }
        }
    }
}
