package com.example.niksey.ui.screens.fullscreen

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.example.niksey.R
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ImagePreviewBottomSheet : BottomSheetDialogFragment() {

    private var imageUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageUrl = arguments?.getString("image_url")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_image_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val photoView = view.findViewById<PhotoView>(R.id.preview_image)

        imageUrl?.let { url ->
            Glide.with(this)
                .load(url)
                .into(photoView)
        }

        photoView.setOnClickListener {
            dismiss()
        }
    }

    // === Главное исправление: применяем фон с закруглёнными верхними углами ===
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        // Убираем стандартный фон BottomSheet
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        return dialog
    }

    companion object {
        fun newInstance(imageUrl: String): ImagePreviewBottomSheet {
            val fragment = ImagePreviewBottomSheet()
            val args = Bundle()
            args.putString("image_url", imageUrl)
            fragment.arguments = args
            return fragment
        }
    }
}