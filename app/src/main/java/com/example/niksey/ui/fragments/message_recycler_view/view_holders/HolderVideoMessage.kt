package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.utillits.media.MediaViews
import com.example.niksey.utillits.showToast
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.asTime
import com.example.niksey.utillits.media.MediaCache
import com.google.android.material.card.MaterialCardView
import de.hdodenhof.circleimageview.CircleImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Видеосообщение (круглое, как в Telegram).
 *
 * Как работает:
 *  • Превью: настоящий первый кадр берётся MediaMetadataRetriever'ом из расшифрованного
 *    файла в кэше. Пока файла нет — иконка-заглушка; после скачивания кадр подставится.
 *  • Воспроизведение: ExoPlayer играет локальный файл из кэша (кэш → загрузка → расшифровка,
 *    см. MediaRepository). Одновременно играет только одно видео ([currentPlayingHolder]).
 *  • Длительность на плашке — из MediaMetadataRetriever, а не заглушка.
 *  • thumbnail.tag защищает от подстановки кадра чужого видео в переиспользованную строку.
 */
class HolderVideoMessage(view: View) : RecyclerView.ViewHolder(view), MessageHolder {

    // === ИСХОДЯЩЕЕ ВИДЕО ===
    private val blocUserVideo: MaterialCardView = view.findViewById(R.id.bloc_user_video)
    private val chatUserVideoThumbnail: CircleImageView = view.findViewById(R.id.chat_user_video_thumbnail)
    private val chatUserVideoPlay: ImageView = view.findViewById(R.id.chat_user_video_play)
    private val chatUserVideoDuration: TextView = view.findViewById(R.id.chat_user_video_duration)
    private val chatUserVideoTime: TextView = view.findViewById(R.id.chat_user_video_time)
    private val userPlayerView: PlayerView = view.findViewById(R.id.user_player_view)

    // === ВХОДЯЩЕЕ ВИДЕО ===
    private val blocReceivedVideo: MaterialCardView = view.findViewById(R.id.bloc_received_video)
    private val chatReceivedVideoThumbnail: CircleImageView = view.findViewById(R.id.chat_received_video_thumbnail)
    private val chatReceivedVideoPlay: ImageView = view.findViewById(R.id.chat_received_video_play)
    private val chatReceivedVideoDuration: TextView = view.findViewById(R.id.chat_received_video_duration)
    private val chatReceivedVideoTime: TextView = view.findViewById(R.id.chat_received_video_time)
    private val receivedPlayerView: PlayerView = view.findViewById(R.id.received_player_view)

    private var exoPlayer: ExoPlayer? = null
    private var currentUrl: String? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Чтение метаданных видео — на фоне: retriever тяжелый, нельзя на главном потоке. */
    private val metadataExecutor = Executors.newSingleThreadExecutor()

    companion object {
        /** Играющее сейчас видео: только одно одновременно. */
        var currentPlayingHolder: HolderVideoMessage? = null

        /** Путь → кадр-превью и длительность, чтобы не читать повторно при прокрутке. */
        private val thumbnailCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
        private val durationCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Поколение кэша, для которого заполнены миниатюры (см. MediaCache.clear). */
        private var cachedGeneration = MediaCache.generation

        /** Кэш медиа очищен: миниатюры по путям больше не соответствуют содержимому. */
        fun invalidateCaches() {
            cachedGeneration = MediaCache.generation
            thumbnailCache.clear()
            durationCache.clear()
        }

        /** true — записи ещё соответствуют текущему поколению кэша. */
        private fun cachesValid(): Boolean = cachedGeneration == MediaCache.generation
    }

    override fun drawMessage(view: MessageView) {
        if (view.from == CURRENT_UID) {
            showUserVideo(view)
        } else {
            showReceivedVideo(view)
        }
    }

    private fun showUserVideo(view: MessageView) {
        blocUserVideo.visibility = View.VISIBLE
        blocReceivedVideo.visibility = View.GONE

        chatUserVideoTime.text = view.timeStamp.asTime()

        chatUserVideoThumbnail.visibility = View.VISIBLE
        userPlayerView.visibility = View.GONE
        chatUserVideoPlay.visibility = View.VISIBLE

        chatUserVideoThumbnail.setImageResource(R.drawable.ic_video)
        loadThumbnailFor(view, chatUserVideoThumbnail, chatUserVideoDuration)

        val clickListener = View.OnClickListener {
            MediaViews.withFile(view, { file ->
                togglePlayback(file.absolutePath, userPlayerView, chatUserVideoThumbnail, chatUserVideoPlay)
            }, { showToast("Видео недоступно") })
        }
        chatUserVideoPlay.setOnClickListener(clickListener)
        blocUserVideo.setOnClickListener(clickListener)
    }

    private fun showReceivedVideo(view: MessageView) {
        blocUserVideo.visibility = View.GONE
        blocReceivedVideo.visibility = View.VISIBLE

        chatReceivedVideoTime.text = view.timeStamp.asTime()

        chatReceivedVideoThumbnail.visibility = View.VISIBLE
        receivedPlayerView.visibility = View.GONE
        chatReceivedVideoPlay.visibility = View.VISIBLE

        chatReceivedVideoThumbnail.setImageResource(R.drawable.ic_video)
        loadThumbnailFor(view, chatReceivedVideoThumbnail, chatReceivedVideoDuration)

        val clickListener = View.OnClickListener {
            MediaViews.withFile(view, { file ->
                togglePlayback(file.absolutePath, receivedPlayerView, chatReceivedVideoThumbnail, chatReceivedVideoPlay)
            }, { showToast("Видео недоступно") })
        }
        chatReceivedVideoPlay.setOnClickListener(clickListener)
        blocReceivedVideo.setOnClickListener(clickListener)
    }

    /**
     * Подставляет первый кадр и длительность, когда файл появился в кэше.
     * Если файла ещё нет (не скачан) — показываем заглушку; после скачивания холдер
     * перерисуется через SecureMessaging.onDecryptedUpdate и кадр появится.
     */
    private fun loadThumbnailFor(message: MessageView, thumbnail: CircleImageView, durationView: TextView) {
        MediaViews.withFile(message, { file ->
            val path = file.absolutePath
            thumbnail.tag = path

            // Кэш медиа очищали — старые миниатюры больше не соответствуют файлам
            if (!cachesValid()) invalidateCaches()

            // Кэш строки: кадр и длительность уже читали для этого файла
            thumbnailCache[path]?.let {
                if (thumbnail.tag == path) thumbnail.setImageBitmap(it)
            }
            durationCache[path]?.let {
                if (thumbnail.tag == path) durationView.text = it
            }
            if (thumbnailCache.containsKey(path) && durationCache.containsKey(path)) return@withFile

            metadataExecutor.execute {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val frame: Bitmap? = retriever.frameAtTime
                    val ms = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    val durationText = formatDuration(ms)

                    if (frame != null) thumbnailCache[path] = frame
                    durationCache[path] = durationText

                    handler.post {
                        if (thumbnail.tag == path) {
                            frame?.let { thumbnail.setImageBitmap(it) }
                            durationView.text = durationText
                        }
                    }
                } catch (e: Exception) {
                    // Битый файл: оставляем заглушку, чат не падает
                } finally {
                    runCatching { retriever.release() }
                }
            }
        })
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun togglePlayback(
        url: String,
        playerView: PlayerView,
        thumbnail: CircleImageView,
        playButton: ImageView
    ) {
        // Если это же видео играет — пауза
        if (currentUrl == url && exoPlayer != null) {
            if (exoPlayer?.isPlaying == true) {
                exoPlayer?.pause()
                playButton.visibility = View.VISIBLE
            } else {
                exoPlayer?.play()
                playButton.visibility = View.GONE
            }
            return
        }

        // Останавливаем предыдущее видео (играет только одно одновременно)
        currentPlayingHolder?.let { previous ->
            if (previous != this) previous.stopCurrentPlayback()
        }
        currentPlayingHolder = this

        // Новое видео: пересоздаём плеер
        releasePlayer()

        exoPlayer = ExoPlayer.Builder(itemView.context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(url))))
            // Битый/неполный файл: возвращаем строку к превью, чат не остаётся
            // в «зависшем» состоянии плеера без возможности нажать play заново
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    showToast("Видео недоступно: ${error.errorCodeName}")
                    stopCurrentPlayback()
                }
            })
            prepare()
            playWhenReady = true
        }
        playerView.player = exoPlayer
        currentUrl = url

        // Переключаем UI: превью уходит, видео появляется
        thumbnail.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        playButton.visibility = View.GONE
    }

    fun stopCurrentPlayback() {
        exoPlayer?.pause()

        if (blocUserVideo.visibility == View.VISIBLE) {
            chatUserVideoThumbnail.visibility = View.VISIBLE
            userPlayerView.visibility = View.GONE
            chatUserVideoPlay.visibility = View.VISIBLE
        } else {
            chatReceivedVideoThumbnail.visibility = View.VISIBLE
            receivedPlayerView.visibility = View.GONE
            chatReceivedVideoPlay.visibility = View.VISIBLE
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        currentUrl = null
    }

    override fun onDetach() {
        if (currentPlayingHolder == this) currentPlayingHolder = null
        stopCurrentPlayback()
        releasePlayer()
    }

    override fun onRecycled() {
        stopCurrentPlayback()
        releasePlayer()
    }

    override fun getMessageType(): String = "video"
}
