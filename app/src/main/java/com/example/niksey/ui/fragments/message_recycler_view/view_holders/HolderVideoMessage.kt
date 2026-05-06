package com.example.niksey.ui.fragments.message_recycler_view.view_holders

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.example.niksey.R
import com.example.niksey.ui.fragments.message_recycler_view.views.MessageView
import com.example.niksey.utillits.CURRENT_UID
import com.example.niksey.utillits.asTime
import com.google.android.material.card.MaterialCardView
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var isPlaying = false

    companion object {
        var currentPlayingHolder: HolderVideoMessage? = null
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
        chatUserVideoDuration.text = view.duration ?: "0:15"

        chatUserVideoThumbnail.visibility = View.VISIBLE
        userPlayerView.visibility = View.GONE
        chatUserVideoPlay.visibility = View.VISIBLE

        // Загружаем реальный первый кадр
        loadVideoThumbnail(view.fileUrl, chatUserVideoThumbnail)

        val clickListener = View.OnClickListener {
            togglePlayback(view.fileUrl, userPlayerView, chatUserVideoThumbnail, chatUserVideoPlay)
        }
        chatUserVideoPlay.setOnClickListener(clickListener)
        blocUserVideo.setOnClickListener(clickListener)
    }

    private fun showReceivedVideo(view: MessageView) {
        blocUserVideo.visibility = View.GONE
        blocReceivedVideo.visibility = View.VISIBLE

        chatReceivedVideoTime.text = view.timeStamp.asTime()
        chatReceivedVideoDuration.text = view.duration ?: "0:15"

        chatReceivedVideoThumbnail.visibility = View.VISIBLE
        receivedPlayerView.visibility = View.GONE
        chatReceivedVideoPlay.visibility = View.VISIBLE

        loadVideoThumbnail(view.fileUrl, chatReceivedVideoThumbnail)

        val clickListener = View.OnClickListener {
            togglePlayback(view.fileUrl, receivedPlayerView, chatReceivedVideoThumbnail, chatReceivedVideoPlay)
        }
        chatReceivedVideoPlay.setOnClickListener(clickListener)
        blocReceivedVideo.setOnClickListener(clickListener)
    }

    private fun loadVideoThumbnail(url: String, imageView: CircleImageView) {
        CoroutineScope(Dispatchers.Main).launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(url)
                    val frame = retriever.getFrameAtTime(300000) // 0.3 секунды
                    retriever.release()
                    frame
                } catch (e: Exception) {
                    null
                }
            }
            bitmap?.let {
                imageView.setImageBitmap(it)
            } ?: imageView.setImageResource(R.drawable.ic_video)
        }
    }

    private fun togglePlayback(
        url: String,
        playerView: PlayerView,
        thumbnail: CircleImageView,
        playButton: ImageView
    ) {
        // Если уже играет это видео — пауза
        if (exoPlayer?.isPlaying == true && currentUrl == url) {
            exoPlayer?.pause()
            isPlaying = false
            playButton.visibility = View.VISIBLE
            return
        }

        // Останавливаем предыдущее видео
        currentPlayingHolder?.let { previous ->
            if (previous != this) previous.stopCurrentPlayback()
        }
        currentPlayingHolder = this

        // Если новое видео
        if (currentUrl != url || exoPlayer == null) {
            releasePlayer()

            exoPlayer = ExoPlayer.Builder(itemView.context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }

            playerView.player = exoPlayer
            currentUrl = url
        } else {
            exoPlayer?.play()
        }

        // Переключаем UI
        thumbnail.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        playButton.visibility = View.GONE
        isPlaying = true
    }

    private fun stopCurrentPlayback() {
        exoPlayer?.pause()
        isPlaying = false

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
        isPlaying = false
    }

    override fun onDetach() {
        stopCurrentPlayback()
        releasePlayer()
    }

    override fun onRecycled() {
        stopCurrentPlayback()
        releasePlayer()
    }

    override fun getMessageType(): String = "video"
}