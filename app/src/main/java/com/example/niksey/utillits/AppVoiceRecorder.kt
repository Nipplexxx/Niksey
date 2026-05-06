package com.example.niksey.utillits

import android.media.MediaRecorder
import com.example.niksey.utillits.APP_ACTIVITY
import java.io.File

@Suppress("DEPRECATION")
class AppVoiceRecorder {

    private var mMediaRecorder: MediaRecorder? = null
    private lateinit var mFile: File
    private lateinit var mMessageKey: String
    private var isRecording = false

    fun startRecord(messageKey: String) {
        try {
            if (isRecording) return

            mMessageKey = messageKey
            createFileForRecord()

            mMediaRecorder = MediaRecorder().apply {
                reset()
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(mFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true

        } catch (e: Exception) {
            showToast("Ошибка начала записи: ${e.message}")
            releaseRecorder()
        }
    }

    fun stopRecord(onSuccess: (file: File, messageKey: String) -> Unit) {
        if (!isRecording || mMediaRecorder == null) return

        try {
            mMediaRecorder?.stop()
            mMediaRecorder?.release()
            mMediaRecorder = null
            isRecording = false

            onSuccess(mFile, mMessageKey)

        } catch (e: Exception) {
            showToast("Ошибка остановки записи: ${e.message}")
            mFile.delete()
            releaseRecorder()
        }
    }

    fun cancelRecord() {
        if (!isRecording || mMediaRecorder == null) return

        try {
            mMediaRecorder?.stop()
            mMediaRecorder?.release()
            mMediaRecorder = null
            isRecording = false

            // Удаляем файл, так как запись отменена
            if (::mFile.isInitialized) {
                mFile.delete()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            releaseRecorder()
        }
    }

    fun releaseRecorder() {
        try {
            mMediaRecorder?.release()
            mMediaRecorder = null
            isRecording = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createFileForRecord() {
        mFile = File(APP_ACTIVITY.filesDir, "$mMessageKey.3gp")
        if (mFile.exists()) {
            mFile.delete()
        }
        mFile.createNewFile()
    }
}