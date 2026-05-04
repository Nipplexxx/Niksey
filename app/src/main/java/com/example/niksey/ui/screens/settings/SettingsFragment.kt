package com.example.niksey.ui.screens.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
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
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.AppStates
import com.example.niksey.utillits.ChatEncryptionManager
import com.example.niksey.utillits.downloadAndSetImage
import com.example.niksey.utillits.replaceFragment
import com.example.niksey.utillits.restartActivity
import com.example.niksey.utillits.showToast
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
                    getUrlFromStorage(path) {
                        putUrlToDatabase(it) {
                            view?.findViewById<CircleImageView>(R.id.settings_user_photo)
                                ?.downloadAndSetImage(it)   // ← исправлено

                            showToast(getString(R.string.toast_data_update))
                            USER.photoUrl = it
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
        APP_ACTIVITY.title = getString(R.string.personal_account)
        setHasOptionsMenu(true)
        initFields()
        KeyboardUtil.hideKeyboard(activity)
    }

    private fun initFields() {
        // Заполняем данные
        view?.findViewById<TextView>(R.id.settings_bio)?.text = USER.bio
        view?.findViewById<TextView>(R.id.settings_full_name)?.text = USER.fullname
        view?.findViewById<TextView>(R.id.settings_phone_number)?.text = USER.phone
        view?.findViewById<TextView>(R.id.settings_status)?.text =
            if (AppStates.getCurrentState() == AppStates.ONLINE) "Online" else "Offline"
        view?.findViewById<TextView>(R.id.settings_username)?.text = USER.username
        view?.findViewById<TextView>(R.id.settings_email)?.text = maskString(USER.email)
        view?.findViewById<TextView>(R.id.settings_password)?.text = maskString(USER.password)

        // Кликеры
        view?.findViewById<ConstraintLayout>(R.id.settings_btn_change_username)
            ?.setOnClickListener { replaceFragment(ChangeUsernameFragment()) }

        view?.findViewById<ConstraintLayout>(R.id.settings_btn_change_bio)
            ?.setOnClickListener { replaceFragment(ChangeBioFragment()) }

        view?.findViewById<CircleImageView>(R.id.settings_shange_photo)
            ?.setOnClickListener { changePhotoUser() }

        view?.findViewById<CircleImageView>(R.id.settings_user_photo)
            ?.downloadAndSetImage(USER.photoUrl)   // ← исправлено

        view?.findViewById<ConstraintLayout>(R.id.settings_btn_change_email)
            ?.setOnClickListener { replaceFragment(ChangeEmailFragment()) }

        view?.findViewById<ConstraintLayout>(R.id.settings_btn_change_password)
            ?.setOnClickListener { replaceFragment(ChangePasswordFragment()) }

        view?.findViewById<ConstraintLayout>(R.id.settings_btn_change_number_phone)
            ?.setOnClickListener { replaceFragment(ChangePhoneFragment()) }
    }

    private fun changePhotoUser() {
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

    private fun maskString(input: String): String {
        return if (input.length > 3) {
            input.substring(0, 3) + "*".repeat(input.length - 3)
        } else {
            input
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        activity?.menuInflater?.inflate(R.menu.settings_actions_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.settings_menu_delete_photo -> {
                removePhotoUser(USER.id) {
                    showToast(getString(R.string.remove_photo_user))
                    restartActivity()
                }
            }

            R.id.settings_menu_clear_cache -> {
                ChatEncryptionManager.clearAndDeleteCache()
                showToast("Кэш шифрования очищен")
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