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
import com.example.niksey.utillits.cache.ContentCache
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
                            showToast(APP_ACTIVITY.getString(R.string.toast_data_update))
                            USER.photoUrl = url
                        }
                    }
                }
            } else {
                showToast("Ошибка при обрезке фото")
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindClicks(view)
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.title = getString(R.string.personal_account)
        APP_ACTIVITY.mAppDrawer.showRoot(AppDrawer.Tab.ACCOUNT)
        setHasOptionsMenu(true)
        KeyboardUtil.hideKeyboard(activity)
        showProfile()
    }

    /** Кнопки настроек назначаются один раз, при создании вью. */
    private fun bindClicks(root: View) {
        root.findViewById<View>(R.id.settings_btn_change_username).setOnClickListener { replaceFragment(ChangeUsernameFragment()) }
        root.findViewById<View>(R.id.settings_btn_change_bio).setOnClickListener { replaceFragment(ChangeBioFragment()) }
        root.findViewById<View>(R.id.settings_btn_change_email).setOnClickListener { replaceFragment(ChangeEmailFragment()) }
        root.findViewById<View>(R.id.settings_btn_change_number_phone).setOnClickListener { replaceFragment(ChangePhoneFragment()) }
        root.findViewById<View>(R.id.settings_btn_about).setOnClickListener { replaceFragment(InformationFragment()) }
        root.findViewById<View>(R.id.settings_btn_clear_cache).setOnClickListener { confirmClearCache() }
        root.findViewById<ImageView>(R.id.settings_shange_photo).setOnClickListener { changePhotoUser() }
    }

    /** Заполняет поля из профиля. Пока профиль не загружен, оставляем поля как есть. */
    private fun showProfile() {
        val root = view ?: return
        if (CURRENT_UID.isBlank() || CURRENT_UID == "null" || USER.id.isBlank()) return

        root.findViewById<TextView>(R.id.settings_full_name).text = USER.fullname.ifEmpty { "Не указано" }
        root.findViewById<TextView>(R.id.settings_username).text =
            "@" + USER.username.ifEmpty { CURRENT_UID }
        root.findViewById<TextView>(R.id.settings_user_id).text = "ID · " + CURRENT_UID.take(12)
        root.findViewById<TextView>(R.id.settings_bio).text = USER.bio.ifEmpty { "Не указано" }
        root.findViewById<TextView>(R.id.settings_phone_number).text = USER.phone.ifEmpty { "Не указан" }
        root.findViewById<TextView>(R.id.settings_email).text = maskString(USER.email)
        root.findViewById<TextView>(R.id.settings_status).text =
            if (AppStates.getCurrentState() == AppStates.ONLINE) "Онлайн" else "Офлайн"
        root.findViewById<CircleImageView>(R.id.settings_user_photo).downloadAndSetImage(USER.photoUrl)
        refreshCacheSize()
    }

    /** Показывает размер кэша медиа (считаем в фоне — папка может быть большой). */
    private fun refreshCacheSize() {
        Thread {
            val size = MediaCache.sizeBytes()
            APP_ACTIVITY.runOnUiThread {
                view?.findViewById<TextView>(R.id.settings_cache_title)?.text =
                    APP_ACTIVITY.getString(R.string.clear_cache_with_size, Formatter.formatFileSize(APP_ACTIVITY, size))
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
                    // Миниатюры видео и длительности голосовых в холдерах привязаны
                    // к путям файлов — после очистки они обязаны устареть
                    com.example.niksey.ui.fragments.message_recycler_view.view_holders.HolderVideoMessage.invalidateCaches()
                    com.example.niksey.ui.fragments.message_recycler_view.view_holders.HolderVoiceMessage.invalidateCaches()
                    // ВАЖНО: Picasso.shutdown() здесь НЕ вызываем — в 2.718 singleton после
                    // shutdown не пересоздаётся, и все картинки в приложении сломаются до
                    // перезапуска. Миниатюры медиакэша устарели выше; аватары Picasso
                    // обновятся своим сроком жизни.
                    APP_ACTIVITY.runOnUiThread {
                        showToast(APP_ACTIVITY.getString(R.string.cache_cleared))
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
                    showToast(APP_ACTIVITY.getString(R.string.remove_photo_user))

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
                ContentCache.clear()
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