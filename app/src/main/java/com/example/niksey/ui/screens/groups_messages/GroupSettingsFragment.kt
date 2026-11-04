package com.example.niksey.ui.screens.groups_messages

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.niksey.R
import com.example.niksey.database.getCommonModel
import com.example.niksey.database.getUrlFromStorage
import com.example.niksey.database.putImageToStorage
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.utillits.*
import com.google.android.material.button.MaterialButton
import de.hdodenhof.circleimageview.CircleImageView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Информация о группе (открывается по нажатию на шапку чата).
 *
 * Все видят участников. Редактировать название, фото и удалять участников может только создатель
 * (роль берётся из groups/{id}/members).
 */
class GroupSettingsFragment() : BaseFragment(R.layout.fragment_group_settings) {

    /** Группа — через Bundle: фрагмент восстанавливается пустым конструктором при пересоздании. */
    private lateinit var group: CommonModel

    private var mAdapter: GroupParticipantsAdapter? = null
    private val members = mutableListOf<GroupMember>()
    private var isCreator = false
    private var cropLauncher: ActivityResultLauncher<CropImageContractOptions>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Группа — из Bundle: при пересоздании фрагмента системой данные не теряются
        @Suppress("DEPRECATION")
        group = requireNotNull(arguments?.getParcelable<CommonModel>(ARG_GROUP)) {
            "GroupSettingsFragment открыт без данных группы"
        }
        cropLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                result.uriContent?.let { uploadGroupPhoto(it) }
            } else {
                showToast(APP_ACTIVITY.getString(R.string.error_cropping_image))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Экран использует собственную стеклянную шапку, как и чат: системную панель скрываем
        APP_ACTIVITY.mAppDrawer.setToolbarVisible(false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.header_title).text = getString(R.string.group_info_title)
        view.findViewById<ImageView>(R.id.header_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val recycler = view.findViewById<RecyclerView>(R.id.participants_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        mAdapter = GroupParticipantsAdapter(members, childFragmentManager) { member -> removeMember(member) }
        recycler.adapter = mAdapter

        view.findViewById<MaterialButton>(R.id.btn_save_name).setOnClickListener {
            val newName = view.findViewById<EditText>(R.id.group_name_edit).text.toString().trim()
            if (newName.isNotEmpty()) updateGroupName(newName)
        }
        view.findViewById<MaterialButton>(R.id.btn_add_participants).setOnClickListener {
            if (isCreator) {
                // Добавление в ЭТУ группу: существующие участники отфильтруются в списке
                replaceFragment(AddContactsFragment.forGroup(group.id))
            } else {
                showToast("Только создатель может добавлять участников")
            }
        }
        // Фото и подсказка «сменить фото» — обе открывают кроппер (только создатель)
        view.findViewById<ImageView>(R.id.group_info_photo).setOnClickListener {
            if (isCreator) cropLauncher?.launch(photoCropOptions())
        }
        view.findViewById<ImageView>(R.id.group_info_photo_edit).setOnClickListener {
            if (isCreator) cropLauncher?.launch(photoCropOptions())
        }

        bindHeader(view)
        loadMembers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mAdapter = null
    }

    // ==================== данные ====================

    private fun bindHeader(view: View) {
        view.findViewById<TextView>(R.id.group_info_name).text = group.fullname
        view.findViewById<CircleImageView>(R.id.group_info_photo).downloadAndSetImage(group.photoUrl)
        view.findViewById<TextView>(R.id.group_info_members).text = getPlurals(members.size)
    }

    /** Роли и профили участников: groups/{id}/members (роль) + users/{uid} (имя и фото). */
    private fun loadMembers() {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_MEMBERS").get()
            .addOnSuccessListener { snapshot ->
                val roles = snapshot.children.mapNotNull { child ->
                    child.key?.let { it to (child.getValue(String::class.java) ?: USER_MEMBER) }
                }.toMap()
                isCreator = roles[CURRENT_UID] == USER_CREATOR

                if (roles.isEmpty()) {
                    applyMembers(emptyList())
                    return@addOnSuccessListener
                }

                val loaded = mutableListOf<GroupMember>()
                val remaining = AtomicInteger(roles.size)
                roles.forEach { (id, role) ->
                    REF_DATABASE_ROOT.child("$NODE_USERS/$id").get()
                        .addOnCompleteListener { task ->
                            val user = task.result?.takeIf { task.isSuccessful }?.getCommonModel()
                            val name = user?.fullname?.ifEmpty { user.username }?.ifEmpty { id } ?: id
                            synchronized(loaded) {
                                loaded.add(GroupMember(id, name, user?.photoUrl ?: "empty", role == USER_CREATOR))
                            }
                            if (remaining.decrementAndGet() == 0) {
                                activity?.runOnUiThread { applyMembers(loaded.toList()) }
                            }
                        }
                }
            }
            .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
    }

    private fun applyMembers(loaded: List<GroupMember>) {
        val v = view ?: return
        members.clear()
        // Создатель первым, остальные по имени
        members.addAll(loaded.sortedWith(compareByDescending<GroupMember> { it.isCreator }.thenBy { it.name }))

        mAdapter?.canRemove = isCreator
        mAdapter?.notifyDataSetChanged()

        v.findViewById<View>(R.id.group_edit_panel).visibility = if (isCreator) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.btn_add_participants).visibility = if (isCreator) View.VISIBLE else View.GONE
        // Подсказка «сменить фото» на аватаре — только создателю
        v.findViewById<View>(R.id.group_info_photo_edit).visibility = if (isCreator) View.VISIBLE else View.GONE
        v.findViewById<EditText>(R.id.group_name_edit).setText(group.fullname)
        bindHeader(v)
    }

    // ==================== действия создателя ====================

    private fun updateGroupName(newName: String) {
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$CHILD_FULLNAME")
            .setValue(newName)
            .addOnSuccessListener {
                group.fullname = newName
                showToast(APP_ACTIVITY.getString(R.string.group_name_updated))
                view?.let { bindHeader(it) }
            }
            .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
    }

    private fun uploadGroupPhoto(uri: android.net.Uri) {
        val path = REF_STORAGE_ROOT.child("$FOLDER_GROUPS_IMAGE/${group.id}")
        putImageToStorage(uri, path) {
            getUrlFromStorage(path) { url ->
                REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$CHILD_PHOTO_URL")
                    .setValue(url)
                    .addOnSuccessListener {
                        group.photoUrl = url
                        showToast(APP_ACTIVITY.getString(R.string.group_photo_updated))
                        view?.let { bindHeader(it) }
                    }
            }
        }
    }

    private fun removeMember(member: GroupMember) {
        if (!isCreator || member.isCreator || member.id == CURRENT_UID) {
            showToast(APP_ACTIVITY.getString(R.string.cannot_remove_self))
            return
        }
        REF_DATABASE_ROOT.child("$NODE_GROUPS/${group.id}/$NODE_MEMBERS/${member.id}")
            .removeValue()
            .addOnSuccessListener {
                showToast(APP_ACTIVITY.getString(R.string.participant_removed))
                members.remove(member)
                mAdapter?.notifyDataSetChanged()
                view?.let { bindHeader(it) }
            }
            .addOnFailureListener { showToast(APP_ACTIVITY.getString(R.string.error_generic, it.message)) }
    }

    private fun photoCropOptions() = CropImageContractOptions(
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

    companion object {
        private const val ARG_GROUP = "arg_group"

        fun newInstance(group: CommonModel): GroupSettingsFragment =
            GroupSettingsFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_GROUP, group) }
            }
    }
}
