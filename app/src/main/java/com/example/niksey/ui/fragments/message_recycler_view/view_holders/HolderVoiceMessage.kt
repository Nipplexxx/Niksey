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
import com.example.niksey.utillits.media.MediaCache
import com.example.niksey.utillits.media.MediaViews
import com.google.android.material.card.MaterialCardView
import kotlin.math.sin

/**
 * Голосовое сообщение: плеер с кнопкой play/pause, волной, прогрессом и длительностью.
 *
 * Как устроено воспроизведение:
 *  • Голосовое физически лежит в зашифрованном кэше; путь даёт MediaViews.withFile
 *    (кэш → загрузка → расшифровка, см. MediaRepository).
 *  • Одновременно играет только одно сообщение: [currentPlayingHolder] останавливает
 *    предыдущее (как в Telegram).
 *  • Длительность читается один раз и кэшируется по пути файла; строка могла быть
 *    переиспользована при прокрутке, поэтому результат пишется только если tag совпадает.
 *  • onDetach/onRecycled освобождают MediaPlayer и Visualizer — иначе звук продолжал бы
 *    играть после ухода строки с экрана.
 */
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

    /** Сообщение, привязанное к этой строке (нужно для кликов). */
    private var boundMessage: MessageView? = null

    companion object {
        /** Играющее сейчас голосовое: только одно одновременно. */
        var currentPlayingHolder: HolderVoiceMessage? = null

        /** url → длительность в формате m:ss, чтобы не открывать каждое голосовое повторно. */
        private val durationCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Поколение кэша, для которого посчитаны длительности (см. MediaCache.clear). */
        @Volatile
        private var cachedGeneration = MediaCache.generation

        /** Кэш медиа очищен: длительности по путям пересчитаются заново. */
        fun invalidateCaches() {
            cachedGeneration = MediaCache.generation
            durationCache.clear()
        }

        private fun cachesValid(): Boolean = cachedGeneration == MediaCache.generation

        /**
         * Один исполнитель для чтения длительностей. Раньше на каждую строку списка
         * создавался свой Thread — при прокрутке их могло быть десятки одновременно.
         */
        private val durationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    override fun drawMessage(view: MessageView) {
        boundMessage = view
        if (view.from == CURRENT_UID) {
            showUserVoice(view)
        } else {
            showReceivedVoice(view)
        }
    }

    private fun showUserVoice(view: MessageView) {
        blocUserVoice.visibility = View.VISIBLE
        blocReceivedVoice.visibility = View.GONE
        chatUserVoiceTime.text = view.timeStamp.asTime()
        preloadDurationFor(view, chatUserVoiceDuration)
    }

    private fun showReceivedVoice(view: MessageView) {
        blocUserVoice.visibility = View.GONE
        blocReceivedVoice.visibility = View.VISIBLE
        chatReceivedVoiceTime.text = view.timeStamp.asTime()
        preloadDurationFor(view, chatReceivedVoiceDuration)
    }

    /** Длительность асинхронная: tag защищает от записи в переиспользованную строку. */
    private fun preloadDurationFor(message: MessageView, durationView: TextView) {
        MediaViews.withFile(message, { file ->
            val path = file.absolutePath
            durationView.tag = path
            preloadDuration(path) { loaded ->
                if (durationView.tag == path) durationView.text = loaded
            }
        })
    }

    private fun preloadDuration(url: String, onLoaded: (String) -> Unit) {
        // Кэш медиа очищали — старые длительности больше не соответствуют файлам
        if (!cachesValid()) invalidateCaches()

        // Длительность уже известна (строка переиспользуется при прокрутке) — не запускаем плеер заново
        durationCache[url]?.let { onLoaded(it); return }

        durationExecutor.execute {
            try {
                val tempPlayer = MediaPlayer().apply {
                    setDataSource(url)
                    prepare()
                    val minutes = duration / 1000 / 60
                    val seconds = (duration / 1000) % 60
                    val durationText = String.format("%d:%02d", minutes, seconds)
                    durationCache[url] = durationText
                    handler.post { onLoaded(durationText) }
                    release()
                }
            } catch (e: Exception) {
                handler.post { onLoaded("0:00") }
            }
        }
    }

    override fun onAttach(view: MessageView) {
        boundMessage = view
        chatUserVoicePlay.setOnClickListener {
            togglePlayback(view, chatUserVoicePlay, chatUserVoiceSeekBar, userWaveBars)
        }
        chatReceivedVoicePlay.setOnClickListener {
            togglePlayback(view, chatReceivedVoicePlay, chatReceivedVoiceSeekBar, receivedWaveBars)
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

    /**
     * Нажатие play/pause. Файл берётся из кэша асинхронно; нажатие до готовности
     * просто игнорируется (пользователь нажмёт ещё раз, когда файл появится).
     */
    private fun togglePlayback(
        messageView: MessageView,
        playBtn: ImageView,
        seekBar: SeekBar,
        bars: List<View>
    ) {
        MediaViews.withFile(messageView, { file ->
            playFile(file.absolutePath, messageView, playBtn, seekBar, bars)
        })
    }

    private fun playFile(
        url: String,
        messageView: MessageView,
        playBtn: ImageView,
        seekBar: SeekBar,
        bars: List<View>
    ) {
        // Если это же сообщение уже играет — пауза
        if (currentUrl == url && mediaPlayer != null) {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                isPlaying = false
                playBtn.setImageResource(R.drawable.ic_voice_play)
                stopWaveAnimation(bars)
                stopVisualizer()
                handler.removeCallbacks(updateSeekBar)
            } else {
                resumePlayback(playBtn, seekBar, bars)
            }
            return
        }

        // Останавливаем предыдущее сообщение (играет только одно одновременно)
        currentPlayingHolder?.let { previous ->
            if (previous != this) previous.pauseCurrentPlayback()
        }
        currentPlayingHolder = this

        // Новое сообщение: пересоздаём плеер
        releaseMediaPlayer()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener {
                start()
                this@HolderVoiceMessage.isPlaying = true
                playBtn.setImageResource(R.drawable.ic_voice_pause)
                seekBar.max = duration
                seekBar.progress = 0
                startWaveAnimation(bars)
                startVisualizer(bars)
                handler.post(updateSeekBar)
            }
            setOnCompletionListener {
                this@HolderVoiceMessage.isPlaying = false
                playBtn.setImageResource(R.drawable.ic_voice_play)
                seekBar.progress = 0
                stopWaveAnimation(bars)
                stopVisualizer()
                handler.removeCallbacks(updateSeekBar)
                // Дослушали: следующее нажатие начнёт с нуля
                currentPlayingHolder = null
                releaseMediaPlayer()
                currentUrl = null
            }
            setOnErrorListener { _, _, _ ->
                playBtn.setImageResource(R.drawable.ic_voice_play)
                true
            }
            prepareAsync()
        }
        currentUrl = url
    }

    /** Возобновление после паузы (тот же файл, плеер уже готов). */
    private fun resumePlayback(playBtn: ImageView, seekBar: SeekBar, bars: List<View>) {
        mediaPlayer?.start()
        isPlaying = true
        playBtn.setImageResource(R.drawable.ic_voice_pause)
        startWaveAnimation(bars)
        startVisualizer(bars)
        handler.post(updateSeekBar)
    }

    /** Останавливает это сообщение, если играет его (вызывается из другой строки). */
    fun pauseCurrentPlayback() {
        mediaPlayer?.pause()
        isPlaying = false
        chatUserVoicePlay.setImageResource(R.drawable.ic_voice_play)
        chatReceivedVoicePlay.setImageResource(R.drawable.ic_voice_play)
        stopWaveAnimation(userWaveBars)
        stopWaveAnimation(receivedWaveBars)
        stopVisualizer()
        handler.removeCallbacks(updateSeekBar)
    }

    private fun startVisualizer(bars: List<View>) {
        stopVisualizer()
        // Visualizer требует разрешения на запись аудио и может не запуститься на некоторых устройствах —
        // в этом случае волна просто остаётся статичной, а чат не падает.
        try {
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
        } catch (e: Exception) {
            visualizer = null
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
