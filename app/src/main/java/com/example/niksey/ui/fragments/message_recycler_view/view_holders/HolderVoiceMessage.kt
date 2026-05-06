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
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.asTime
import com.google.android.material.card.MaterialCardView
import kotlin.math.sin

class HolderVoiceMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    // === ИСХОДЯЩЕЕ ===
    private val blocUserVoice: MaterialCardView = view.findViewById(R.id.bloc_user_voice)
    private val chatUserVoicePlay: ImageView = view.findViewById(R.id.chat_user_voice_play)
    private val chatUserVoiceSeekBar: SeekBar = view.findViewById(R.id.chat_user_voice_seekbar)
    private val chatUserVoiceDuration: TextView = view.findViewById(R.id.chat_user_voice_duration)
    private val chatUserVoiceTime: TextView = view.findViewById(R.id.chat_user_voice_time)

    // === ВХОДЯЩЕЕ ===
    private val blocReceivedVoice: MaterialCardView = view.findViewById(R.id.bloc_received_voice)
    private val chatReceivedVoicePlay: ImageView = view.findViewById(R.id.chat_received_voice_play)
    private val chatReceivedVoiceSeekBar: SeekBar = view.findViewById(R.id.chat_received_voice_seekbar)
    private val chatReceivedVoiceDuration: TextView = view.findViewById(R.id.chat_received_voice_duration)
    private val chatReceivedVoiceTime: TextView = view.findViewById(R.id.chat_received_voice_time)

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
        } else {
            showReceivedVoice(view)
        }
    }

    private fun showUserVoice(view: MessageView) {
        blocUserVoice.visibility = View.VISIBLE
        blocReceivedVoice.visibility = View.GONE
        currentUrl = view.fileUrl
        chatUserVoiceTime.text = view.timeStamp.asTime()
        preloadDuration(view.fileUrl, chatUserVoiceDuration)
    }

    private fun showReceivedVoice(view: MessageView) {
        blocUserVoice.visibility = View.GONE
        blocReceivedVoice.visibility = View.VISIBLE
        currentUrl = view.fileUrl
        chatReceivedVoiceTime.text = view.timeStamp.asTime()
        preloadDuration(view.fileUrl, chatReceivedVoiceDuration)
    }

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
        chatUserVoicePlay.setOnClickListener {
            togglePlayback(view, chatUserVoicePlay, chatUserVoiceSeekBar, userWaveBars, true)
        }
        chatReceivedVoicePlay.setOnClickListener {
            togglePlayback(view, chatReceivedVoicePlay, chatReceivedVoiceSeekBar, receivedWaveBars, false)
        }

        chatUserVoiceSeekBar.setOnSeekBarChangeListener(seekBarListener)
        chatReceivedVoiceSeekBar.setOnSeekBarChangeListener(seekBarListener)
    }

    private val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser && mediaPlayer != null) {
                mediaPlayer?.seekTo(progress)
            }
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun togglePlayback(
        messageView: MessageView,
        playBtn: ImageView,
        seekBar: SeekBar,
        bars: List<View>,
        isUser: Boolean
    ) {
        val url = messageView.fileUrl.takeIf { it.isNotBlank() } ?: return

        // Если уже играет это же сообщение — пауза
        if (mediaPlayer?.isPlaying == true && currentUrl == url) {
            mediaPlayer?.pause()
            isPlaying = false
            playBtn.setImageResource(if (isUser) R.drawable.ic_play_purple else R.drawable.ic_play_blue)
            stopWaveAnimation(bars)
            stopVisualizer()
            handler.removeCallbacks(updateSeekBar)
            return
        }

        // Останавливаем предыдущее сообщение
        currentPlayingHolder?.let { previous ->
            if (previous != this) previous.pauseCurrentPlayback()
        }
        currentPlayingHolder = this

        // Если новое сообщение или первый запуск
        if (currentUrl != url || mediaPlayer == null) {
            releaseMediaPlayer()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener {
                    start()
                    this@HolderVoiceMessage.isPlaying = true
                    playBtn.setImageResource(if (isUser) R.drawable.ic_stop_purple else R.drawable.ic_stop_purple)
                    seekBar.max = duration
                    startWaveAnimation(bars)
                    startVisualizer(bars)
                    handler.post(updateSeekBar)
                }
                setOnCompletionListener {
                    this@HolderVoiceMessage.isPlaying = false
                    playBtn.setImageResource(if (isUser) R.drawable.ic_play_purple else R.drawable.ic_play_blue)
                    seekBar.progress = 0
                    stopWaveAnimation(bars)
                    stopVisualizer()
                    handler.removeCallbacks(updateSeekBar)
                    currentPlayingHolder = null
                }
            }
        } else {
            mediaPlayer?.start()
            isPlaying = true
            playBtn.setImageResource(if (isUser) R.drawable.ic_stop_purple else R.drawable.ic_stop_purple)
            startWaveAnimation(bars)
            startVisualizer(bars)
            handler.post(updateSeekBar)
        }
        currentUrl = url
    }

    fun pauseCurrentPlayback() {
        mediaPlayer?.pause()
        isPlaying = false
        chatUserVoicePlay.setImageResource(R.drawable.ic_play_purple)
        chatReceivedVoicePlay.setImageResource(R.drawable.ic_play_blue)
        stopWaveAnimation(userWaveBars)
        stopWaveAnimation(receivedWaveBars)
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
                    val currentSeekBar = if (blocUserVoice.visibility == View.VISIBLE)
                        chatUserVoiceSeekBar else chatReceivedVoiceSeekBar
                    currentSeekBar.progress = it.currentPosition
                    handler.postDelayed(this, 300)
                }
            }
        }
    }

    private fun startWaveAnimation(bars: List<View>) {
        stopWaveAnimation(bars)
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedFraction
                bars.forEachIndexed { index, bar ->
                    val scale = 0.4f + (sin((progress + index) * 3.0) * 0.6f).toFloat()
                    bar.scaleY = scale.coerceIn(0.4f, 1f)
                }
            }
            start()
        }
    }

    private fun stopWaveAnimation(bars: List<View>) {
        waveAnimator?.cancel()
        waveAnimator = null
        bars.forEach { it.scaleY = 1f }
    }

    private fun releaseMediaPlayer() {
        stopVisualizer()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        stopWaveAnimation(userWaveBars)
        stopWaveAnimation(receivedWaveBars)
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