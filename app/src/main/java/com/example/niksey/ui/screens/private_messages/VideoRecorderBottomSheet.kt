package com.example.niksey.ui.screens.private_messages

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.otaliastudios.cameraview.CameraListener
import com.otaliastudios.cameraview.CameraView
import com.otaliastudios.cameraview.VideoResult
import com.otaliastudios.cameraview.controls.Mode
import com.example.niksey.R
import com.example.niksey.utillits.showToast
import java.io.File

class VideoRecorderBottomSheet(
    private val onVideoRecorded: (File) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var cameraView: CameraView
    private lateinit var progressCircle: ProgressBar
    private var isRecording = false
    private var recordedFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val MAX_TIME = 60000L
    private var progress = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_video_recorder, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraView = view.findViewById(R.id.camera_view)
        progressCircle = view.findViewById(R.id.progress_circle)

        cameraView.setLifecycleOwner(viewLifecycleOwner)
        cameraView.mode = Mode.VIDEO

        cameraView.addCameraListener(object : CameraListener() {
            override fun onVideoTaken(result: VideoResult) {
                onVideoRecorded(result.file)
                dismiss()
            }
        })

        // === ЗАПИСЬ ПО ЗАЖАТИЮ НА КАМЕРУ (как в Telegram) ===
        cameraView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) stopRecording()
                    true
                }
                else -> false
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return

        isRecording = true
        recordedFile = File(requireContext().cacheDir, "video_${System.currentTimeMillis()}.mp4")

        cameraView.takeVideo(recordedFile!!)
        progressCircle.visibility = View.VISIBLE
        progress = 0

        // Анимация прогресса
        handler.post(object : Runnable {
            override fun run() {
                if (!isRecording) return
                progress += 100
                progressCircle.progress = (progress * 100 / MAX_TIME).toInt()

                if (progress < MAX_TIME) {
                    handler.postDelayed(this, 100)
                } else {
                    stopRecording()
                }
            }
        })
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        cameraView.stopVideo()
        progressCircle.visibility = View.GONE
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        if (::cameraView.isInitialized) cameraView.destroy()
    }
}