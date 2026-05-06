@file:Suppress("DEPRECATION")

package com.example.niksey.ui.screens.settings

import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.constraintlayout.widget.ConstraintLayout
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.niksey.R
import com.example.niksey.database.*
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.utillits.*
import com.mikepenz.materialize.util.KeyboardUtil
import de.hdodenhof.circleimageview.CircleImageView

class SettingsFragment : BaseFragment(R.layout.fragment_settings) {

    private lateinit var cropImageLauncher: ActivityResultLauncher<CropImageContractOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val uri = result.uriContent ?: return@registerForActivityResult
                val path = REF_STORAGE_ROOT.child(FOLDER_PROFILE_IMAGE).child(CURRENT_UID)

                putImageToStorage(uri, path) {
                    getUrlFromStorage(path) { url ->
                        putUrlToDatabase(url) {
                            view?.findViewById<CircleImageView>(R.id.settings_user_photo)
                                ?.downloadAndSetImage(url)
                            showToast(getString(R.string.toast_data_update))
                            USER.photoUrl = url
                        }
                    }
                }
            } else {
                showToast("Ошибка при обрезке фото")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            APP_ACTIVITY.title = getString(R.string.personal_account)
            setHasOptionsMenu(true)
            initFieldsSafe()
            KeyboardUtil.hideKeyboard(activity)
        } catch (e: Exception) {
            Log.e("SettingsFragment", "КРАШ В onResume", e)
            showToast("Критическая ошибка:\n${e.javaClass.simpleName}\n${e.message}")
        }
    }

    private fun initFieldsSafe() {
        try {
            if (CURRENT_UID.isNullOrBlank() || CURRENT_UID == "null") {
                showToast("Пользователь не авторизован")
                return
            }

            if (USER.id.isNullOrBlank()) {
                showToast("Данные пользователя загружаются...")
                return
            }

            (view?.findViewById<View>(R.id.settings_full_name) as? TextView)?.text = USER.fullname ?: "Не указано"
            (view?.findViewById<View>(R.id.settings_username) as? TextView)?.text = USER.username?.takeIf { it.isNotBlank() } ?: CURRENT_UID
            (view?.findViewById<View>(R.id.settings_bio) as? TextView)?.text = USER.bio ?: "Не указано"
            (view?.findViewById<View>(R.id.settings_phone_number) as? TextView)?.text = USER.phone ?: "Не указан"
            (view?.findViewById<View>(R.id.settings_email) as? TextView)?.text = maskString(USER.email)
            (view?.findViewById<View>(R.id.settings_status) as? TextView)?.text =
                if (AppStates.getCurrentState() == AppStates.ONLINE) "Онлайн" else "Офлайн"

            (view?.findViewById<View>(R.id.settings_user_photo) as? CircleImageView)
                ?.downloadAndSetImage(USER.photoUrl ?: "")

            (view?.findViewById<View>(R.id.settings_btn_change_username) as? ConstraintLayout)
                ?.setOnClickListener { replaceFragment(ChangeUsernameFragment()) }

            (view?.findViewById<View>(R.id.settings_btn_change_bio) as? ConstraintLayout)
                ?.setOnClickListener { replaceFragment(ChangeBioFragment()) }

            (view?.findViewById<View>(R.id.settings_shange_photo) as? ImageView)
                ?.setOnClickListener { changePhotoUser() }

            (view?.findViewById<View>(R.id.settings_btn_change_email) as? ConstraintLayout)
                ?.setOnClickListener { replaceFragment(ChangeEmailFragment()) }

            (view?.findViewById<View>(R.id.settings_btn_change_number_phone) as? ConstraintLayout)
                ?.setOnClickListener { replaceFragment(ChangePhoneFragment()) }

        } catch (e: Exception) {
            Log.e("SettingsFragment", "ОШИБКА", e)
            showToast("Ошибка:\n${e.javaClass.simpleName}\n${e.message}")
        }
    }

    private fun maskString(input: String?): String {
        val text = input?.trim() ?: return "Не указано"
        return if (text.length > 3) {
            text.substring(0, 3) + "*".repeat(text.length - 3)
        } else text
    }

    private fun changePhotoUser() {
        val options = CropImageContractOptions(
            uri = null,
            cropImageOptions = CropImageOptions(
                aspectRatioX = 1, aspectRatioY = 1,
                fixAspectRatio = true,
                outputRequestWidth = 250, outputRequestHeight = 250,
                cropShape = CropImageView.CropShape.OVAL
            )
        )
        cropImageLauncher.launch(options)
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        activity?.menuInflater?.inflate(R.menu.settings_actions_menu, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.settings_menu_delete_photo -> {
                removePhotoUser {
                    showToast(getString(R.string.remove_photo_user))

                    view?.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.settings_user_photo)
                        ?.setImageResource(R.drawable.ic_person)

                    USER.photoUrl = ""
                }
            }
            R.id.settings_menu_clear_cache -> {
                try {
                    ChatEncryptionManager.clearAndDeleteCache()
                    showToast("Кэш шифрования очищен")
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Ошибка очистки кэша", e)
                    showToast("Ошибка очистки кэша:\n${e.message}")
                }
            }
            R.id.settings_menu_exit -> {
                AppStates.updateState(AppStates.OFFLINE)
                AUTH.signOut()
                ChatEncryptionManager.clearAndDeleteCache()
                restartActivity()
            }
            R.id.settings_menu_change_name -> {
                replaceFragment(ChangeNameFragment())
            }
        }
        return true
    }
}