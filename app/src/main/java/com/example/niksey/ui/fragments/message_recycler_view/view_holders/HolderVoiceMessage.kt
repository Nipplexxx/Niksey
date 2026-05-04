package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.database.CURRENT_UID
import com.example.niksey.database.REF_DATABASE_ROOT
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageHolder
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.APP_ACTIVITY
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.showToast
import com.google.android.material.card.MaterialCardView

class HolderVoiceMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    private val blocUserVoice: MaterialCardView = view.findViewById(R.id.bloc_user_voice)
    private val chatUserVoiceTime: TextView = view.findViewById(R.id.chat_user_voice_time)
    private val chatUserVoicePlay: ImageView = view.findViewById(R.id.chat_user_voice_play)
    private val chatUserVoiceSeekBar: SeekBar = view.findViewById(R.id.chat_user_voice_seekbar)
    private val chatUserVoiceDuration: TextView = view.findViewById(R.id.chat_user_voice_duration)

    private val blocReceivedVoice: MaterialCardView = view.findViewById(R.id.bloc_received_voice)
    private val chatReceivedVoiceTime: TextView = view.findViewById(R.id.chat_received_voice_time)
    private val chatReceivedVoicePlay: ImageView = view.findViewById(R.id.chat_received_voice_play)
    private val chatReceivedVoiceSeekBar: SeekBar = view.findViewById(R.id.chat_received_voice_seekbar)
    private val chatReceivedVoiceDuration: TextView = view.findViewById(R.id.chat_received_voice_duration)

    private val userWaveBars = listOf(
        view.findViewById<View>(R.id.user_bar1),
        view.findViewById<View>(R.id.user_bar2),
        view.findViewById<View>(R.id.user_bar3),
        view.findViewById<View>(R.id.user_bar4),
        view.findViewById<View>(R.id.user_bar5),
        view.findViewById<View>(R.id.user_bar6)
    )

    private val receivedWaveBars = listOf(
        view.findViewById<View>(R.id.received_bar1),
        view.findViewById<View>(R.id.received_bar2),
        view.findViewById<View>(R.id.received_bar3),
        view.findViewById<View>(R.id.received_bar4),
        view.findViewById<View>(R.id.received_bar5),
        view.findViewById<View>(R.id.received_bar6)
    )

    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentUrl: String? = null
    private var waveAnimator: ValueAnimator? = null

    companion object {
        var currentPlayingHolder: HolderVoiceMessage? = null
    }

    override fun drawMessage(view: MessageView) {
        if (view.from == CURRENT_UID) {
            showUserVoice(view)
            preloadInitialWaveform(view.fileUrl, userWaveBars)
            preloadDuration(view.fileUrl, chatUserVoiceDuration)
        } else {
            showReceivedVoice(view)
            preloadInitialWaveform(view.fileUrl, receivedWaveBars)
            preloadDuration(view.fileUrl, chatReceivedVoiceDuration)
        }
    }

    private fun showUserVoice(view: MessageView) {
        blocUserVoice.visibility = View.VISIBLE
        blocReceivedVoice.visibility = View.GONE
        chatUserVoiceTime.text = view.timeStamp.asTime()
        currentUrl = view.fileUrl
    }

    private fun showReceivedVoice(view: MessageView) {
        blocUserVoice.visibility = View.GONE
        blocReceivedVoice.visibility = View.VISIBLE
        chatReceivedVoiceTime.text = view.timeStamp.asTime()
        currentUrl = view.fileUrl
    }

    // ==================== ПРЕДПРОСМОТР НАЧАЛА ЗВУКА ====================
    private fun preloadInitialWaveform(url: String, bars: List<View>) {
        Thread {
            try {
                val tempPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    prepare()
                }

                val tempVisualizer = Visualizer(tempPlayer.audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[0]
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            waveform?.let {
                                handler.post {
                                    bars.forEachIndexed { index, bar ->
                                        val amplitude = (it[index % it.size].toInt() and 0xFF) / 255f
                                        val targetHeight = (8 + amplitude * 20).toInt()
                                        bar.layoutParams = bar.layoutParams.apply { height = targetHeight }
                                        bar.requestLayout()
                                    }
                                }
                            }
                        }
                        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                    }, Visualizer.getMaxCaptureRate() / 2, true, false)
                    enabled = true
                }

                // Даём время на захват
                Thread.sleep(80)
                tempVisualizer.enabled = false
                tempVisualizer.release()
                tempPlayer.release()

            } catch (e: Exception) {
                // Если не получилось — оставляем дефолтные высоты
            }
        }.start()
    }

    // ==================== ДЛИТЕЛЬНОСТЬ ====================
    private fun preloadDuration(url: String, durationTextView: TextView) {
        Thread {
            try {
                val tempPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    prepare()
                    val minutes = duration / 1000 / 60
                    val seconds = (duration / 1000) % 60
                    val durationText = String.format("%d:%02d", minutes, seconds)
                    handler.post { durationTextView.text = durationText }
                    release()
                }
            } catch (e: Exception) {
                handler.post { durationTextView.text = "0:00" }
            }
        }.start()
    }

    override fun onAttach(view: MessageView) {
        chatUserVoicePlay.setOnClickListener { togglePlayback(view, chatUserVoicePlay, chatUserVoiceSeekBar) }
        chatReceivedVoicePlay.setOnClickListener { togglePlayback(view, chatReceivedVoicePlay, chatReceivedVoiceSeekBar) }

        chatUserVoiceSeekBar.setOnSeekBarChangeListener(seekBarListener)
        chatReceivedVoiceSeekBar.setOnSeekBarChangeListener(seekBarListener)
    }

    private val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser && mediaPlayer != null) mediaPlayer?.seekTo(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun togglePlayback(messageView: MessageView, playBtn: ImageView, seekBar: SeekBar) {
        val url = messageView.fileUrl.takeIf { it.isNotBlank() } ?: return

        if (mediaPlayer?.isPlaying == true && currentUrl == url) {
            mediaPlayer?.pause()
            isPlaying = false
            playBtn.setImageResource(R.drawable.ic_play_blue)
            stopWaveAnimation()
            stopVisualizer()
            handler.removeCallbacks(updateSeekBar)
            return
        }

        currentPlayingHolder?.let { previous ->
            if (previous != this) previous.pauseCurrentPlayback()
        }
        currentPlayingHolder = this

        if (currentUrl != url || mediaPlayer == null) {
            releaseMediaPlayer()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener {
                    start()
                    this@HolderVoiceMessage.isPlaying = true
                    playBtn.setImageResource(R.drawable.ic_stop_blue)
                    seekBar.max = duration
                    startWaveAnimation(if (blocUserVoice.visibility == View.VISIBLE) userWaveBars else receivedWaveBars)
                    startVisualizer(if (blocUserVoice.visibility == View.VISIBLE) userWaveBars else receivedWaveBars)
                    handler.post(updateSeekBar)
                }
                setOnCompletionListener {
                    this@HolderVoiceMessage.isPlaying = false
                    playBtn.setImageResource(R.drawable.ic_play_blue)
                    seekBar.progress = 0
                    stopWaveAnimation()
                    stopVisualizer()
                    handler.removeCallbacks(updateSeekBar)
                    currentPlayingHolder = null
                }
            }
        } else {
            mediaPlayer?.start()
            isPlaying = true
            playBtn.setImageResource(R.drawable.ic_stop_blue)
            startWaveAnimation(if (blocUserVoice.visibility == View.VISIBLE) userWaveBars else receivedWaveBars)
            startVisualizer(if (blocUserVoice.visibility == View.VISIBLE) userWaveBars else receivedWaveBars)
            handler.post(updateSeekBar)
        }
        currentUrl = url
    }

    fun pauseCurrentPlayback() {
        mediaPlayer?.pause()
        isPlaying = false
        chatUserVoicePlay.setImageResource(R.drawable.ic_play_blue)
        chatReceivedVoicePlay.setImageResource(R.drawable.ic_play_blue)
        stopWaveAnimation()
        stopVisualizer()
        handler.removeCallbacks(updateSeekBar)
    }

    private fun startVisualizer(bars: List<View>) {
        stopVisualizer()
        mediaPlayer?.audioSessionId?.let { sessionId ->
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        waveform?.let {
                            handler.post { updateBarsFromWaveform(bars, it) }
                        }
                    }
                    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        }
    }

    private fun updateBarsFromWaveform(bars: List<View>, waveform: ByteArray) {
        bars.forEachIndexed { index, bar ->
            val amplitude = (waveform[index % waveform.size].toInt() and 0xFF) / 255f
            val targetHeight = (6 + amplitude * 22).toInt()
            bar.layoutParams = bar.layoutParams.apply { height = targetHeight }
            bar.requestLayout()
        }
    }

    private fun stopVisualizer() {
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
    }

    private val updateSeekBar = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    val currentSeekBar = if (blocUserVoice.visibility == View.VISIBLE) chatUserVoiceSeekBar else chatReceivedVoiceSeekBar
                    currentSeekBar.progress = it.currentPosition
                    handler.postDelayed(this, 300)
                }
            }
        }
    }

    private fun startWaveAnimation(bars: List<View>) {
        stopWaveAnimation()
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedFraction
                bars.forEachIndexed { index, bar ->
                    val scale = 0.4f + (Math.sin((progress + index) * 3.0) * 0.6f).toFloat()
                    bar.scaleY = scale.coerceIn(0.4f, 1f)
                }
            }
            start()
        }
    }

    private fun stopWaveAnimation() {
        waveAnimator?.cancel()
        waveAnimator = null
        userWaveBars.forEach { it.scaleY = 1f }
        receivedWaveBars.forEach { it.scaleY = 1f }
    }

    private fun releaseMediaPlayer() {
        stopVisualizer()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        stopWaveAnimation()
        handler.removeCallbacks(updateSeekBar)
    }

    override fun onDetach() {
        if (currentPlayingHolder == this) currentPlayingHolder = null
        releaseMediaPlayer()
        chatUserVoicePlay.setOnClickListener(null)
        chatReceivedVoicePlay.setOnClickListener(null)
    }

    override fun onRecycled() {
        releaseMediaPlayer()
    }

    override fun getMessageType(): String = "voice"
}