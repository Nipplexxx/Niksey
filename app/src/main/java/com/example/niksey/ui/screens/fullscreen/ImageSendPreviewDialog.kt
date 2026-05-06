package com.example.niksey.ui.screens.fullscreen

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.example.niksey.R

class ImageSendPreviewDialog : DialogFragment() {

    private var imageUri: Uri? = null
    private var onSendClick: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageUri = arguments?.getParcelable("image_uri")
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_fullscreen_image_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val previewImage = view.findViewById<com.github.chrisbanes.photoview.PhotoView>(R.id.preview_image)
        val btnSend = view.findViewById<View>(R.id.btn_send)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel)

        imageUri?.let { uri ->
            Glide.with(this)
                .load(uri)
                .into(previewImage)
        }

        btnSend.setOnClickListener {
            imageUri?.let { uri -> onSendClick?.invoke(uri) }
            dismiss()
        }

        btnCancel.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    companion object {
        fun newInstance(uri: Uri, onSend: (Uri) -> Unit): ImageSendPreviewDialog {
            val dialog = ImageSendPreviewDialog()
            val args = Bundle()
            args.putParcelable("image_uri", uri)
            dialog.arguments = args
            dialog.onSendClick = onSend
            return dialog
        }
    }
}