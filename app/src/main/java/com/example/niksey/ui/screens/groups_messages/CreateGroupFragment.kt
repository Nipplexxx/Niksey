@file:Suppress("DEPRECATION")

package com.example.niksey.ui.screens.groups_messages

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.niksey.R
import com.example.niksey.database.createGroupToDatabase
import com.example.niksey.models.CommonModel
import com.example.niksey.ui.screens.base_fragment.BaseFragment
import com.example.niksey.ui.screens.main_list.MainListFragment
import com.example.niksey.utillits.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mikepenz.materialize.util.KeyboardUtil.hideKeyboard

class CreateGroupFragment(private var listContacts: List<CommonModel>) :
    BaseFragment(R.layout.fragment_create_group) {

    private lateinit var mRecyclerView: RecyclerView
    private lateinit var mAdapter: AddContactsAdapter
    private var mUri = Uri.EMPTY
    private lateinit var cropImageLauncher: ActivityResultLauncher<CropImageContractOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                mUri = result.uriContent ?: Uri.EMPTY
                view?.findViewById<ImageView>(R.id.create_group_photo)?.setImageURI(mUri)
            } else {
                val errorMsg = result.error?.message ?: getString(R.string.unknown_error)
                showToast(APP_ACTIVITY.getString(R.string.error_cropping_image, errorMsg))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        APP_ACTIVITY.title = getString(R.string.create_group)
        hideKeyboard(requireActivity())
        initRecyclerView()

        view?.findViewById<ImageView>(R.id.create_group_photo)?.setOnClickListener {
            checkCameraPermissionAndLaunchCropper()
        }

        view?.findViewById<FloatingActionButton>(R.id.create_group_btn_complete)?.setOnClickListener {
            val nameGroup = view?.findViewById<EditText>(R.id.create_group_input_name)?.text.toString()
            if (nameGroup.isEmpty()) {
                showToast(getString(R.string.enter_a_name))
            } else {
                createGroupToDatabase(nameGroup, mUri, listContacts) {
                    replaceFragment(MainListFragment())
                }
            }
        }

        view?.findViewById<EditText>(R.id.create_group_input_name)?.requestFocus()
        view?.findViewById<TextView>(R.id.create_group_counts)?.text = getPlurals(listContacts.size)
    }

    private fun checkCameraPermissionAndLaunchCropper() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {

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

    private fun initRecyclerView() {
        mRecyclerView = view?.findViewById(R.id.create_group_recycle_view)!!
        mAdapter = AddContactsAdapter()
        mRecyclerView.adapter = mAdapter
        listContacts.forEach { mAdapter.updateListItems(it) }
    }
}