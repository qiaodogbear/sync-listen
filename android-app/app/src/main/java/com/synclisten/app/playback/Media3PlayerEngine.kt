package com.synclisten.app.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Media3PlayerEngine @Inject constructor(
    @ApplicationContext context: Context,
) : PlayerEngine {
    private val player = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())
    private var listener: (PlayerEngineEvent) -> Unit = {}
    private val positionTicker = object : Runnable {
        override fun run() {
            if (!player.isPlaying) return
            emitPosition()
            handler.postDelayed(this, POSITION_SAMPLE_MS)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> listener(PlayerEngineEvent.Ready(player.duration.coerceAtLeast(0)))
                    Player.STATE_ENDED -> listener(PlayerEngineEvent.Ended)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener(PlayerEngineEvent.Error(error.message ?: "Media3 playback failed"))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(positionTicker)
                if (isPlaying) handler.post(positionTicker)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                emitPosition()
            }
        })
    }

    override fun setEventListener(listener: (PlayerEngineEvent) -> Unit) {
        this.listener = listener
    }

    override fun load(localPath: String) {
        player.setMediaItem(MediaItem.fromUri(localPath))
        player.prepare()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun release() {
        handler.removeCallbacks(positionTicker)
        player.release()
    }

    private fun emitPosition() {
        listener(PlayerEngineEvent.Position(player.currentPosition, player.duration.coerceAtLeast(0)))
    }

    private companion object {
        const val POSITION_SAMPLE_MS = 500L
    }
}
