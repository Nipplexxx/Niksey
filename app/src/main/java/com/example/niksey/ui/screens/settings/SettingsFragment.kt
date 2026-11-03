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
import com.example.niksey.ui.objects.AppDrawer
import com.example.niksey.ui.screens.other_fragment.InformationFragment
import com.example.niksey.utillits.media.MediaCache
import com.example.niksey.utillits.signal.SecureMessaging
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import com.example.niksey.utillits.*
import com.mikepenz.materialize.util.KeyboardUtil
import de.hdodenhof.circleimageview.CircleImageView
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment(R.layout.fragment_settings) {

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
            APP_ACTIVITY.mAppDrawer.showRoot(AppDrawer.Tab.ACCOUNT)
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
            if (CURRENT_UID.isBlank() || CURRENT_UID == "null") {
                showToast("Пользователь не авторизован")
                return
            }

            if (USER.id.isBlank()) {
                showToast("Данные пользователя загружаются...")
                return
            }

            (view?.findViewById<View>(R.id.settings_full_name) as? TextView)?.text = USER.fullname ?: "Не указано"
            (view?.findViewById<View>(R.id.settings_username) as? TextView)?.text =
                "@" + (USER.username?.takeIf { it.isNotBlank() } ?: CURRENT_UID)
            (view?.findViewById<View>(R.id.settings_user_id) as? TextView)?.text =
                "ID · " + CURRENT_UID.take(12)
            (view?.findViewById<View>(R.id.settings_bio) as? TextView)?.text = USER.bio ?: "Не указано"
            (view?.findViewById<View>(R.id.settings_phone_number) as? TextView)?.text = USER.phone ?: "Не указан"
            (view?.findViewById<View>(R.id.settings_email) as? TextView)?.text = maskString(USER.email)
            (view?.findViewById<View>(R.id.settings_status) as? TextView)?.text =
                if (AppStates.getCurrentState() == AppStates.ONLINE) "Онлайн" else "Офлайн"

            (view?.findViewById<View>(R.id.settings_user_photo) as? CircleImageView)
                ?.downloadAndSetImage(USER.photoUrl ?: "")

            (view?.findViewById<View>(R.id.settings_btn_change_username) as? View)
                ?.setOnClickListener { replaceFragment(ChangeUsernameFragment()) }

            (view?.findViewById<View>(R.id.settings_btn_change_bio) as? View)
                ?.setOnClickListener { replaceFragment(ChangeBioFragment()) }

            (view?.findViewById<View>(R.id.settings_shange_photo) as? ImageView)
                ?.setOnClickListener { changePhotoUser() }

            (view?.findViewById<View>(R.id.settings_btn_about) as? View)
                ?.setOnClickListener { replaceFragment(InformationFragment()) }

            (view?.findViewById<View>(R.id.settings_btn_clear_cache) as? View)
                ?.setOnClickListener { confirmClearCache() }
            refreshCacheSize()

            (view?.findViewById<View>(R.id.settings_btn_change_email) as? View)
                ?.setOnClickListener { replaceFragment(ChangeEmailFragment()) }

            (view?.findViewById<View>(R.id.settings_btn_change_number_phone) as? View)
                ?.setOnClickListener { replaceFragment(ChangePhoneFragment()) }

        } catch (e: Exception) {
            Log.e("SettingsFragment", "ОШИБКА", e)
            showToast("Ошибка:\n${e.javaClass.simpleName}\n${e.message}")
        }
    }

    /** Показывает размер кэша медиа (считаем в фоне — папка может быть большой). */
    private fun refreshCacheSize() {
        Thread {
            val size = MediaCache.sizeBytes()
            APP_ACTIVITY.runOnUiThread {
                view?.findViewById<TextView>(R.id.settings_cache_title)?.text =
                    getString(R.string.clear_cache_with_size, Formatter.formatFileSize(APP_ACTIVITY, size))
            }
        }.start()
    }

    /** Очищает только кэш медиа и аватаров. Сообщения, ключи и чаты остаются. */
    private fun confirmClearCache() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_cache_title)
            .setMessage(R.string.clear_cache_message)
            .setPositiveButton(R.string.clear_cache_title) { _, _ ->
                Thread {
                    MediaCache.clear()
                    APP_ACTIVITY.runOnUiThread {
                        showToast(getString(R.string.cache_cleared))
                        refreshCacheSize()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                SecureMessaging.wipe()
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