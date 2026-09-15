package com.synclisten.app.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Media3PlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlayerEngine {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listener: (PlayerEngineEvent) -> Unit = {}
    private val positionTicker = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!current.isPlaying) return
            emitPosition(current)
            handler.postDelayed(this, 250)
        }
    }

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(context).build().also { current ->
        current.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        current.setHandleAudioBecomingNoisy(true)
        current.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== current) return
                when (playbackState) {
                    Player.STATE_READY -> {
                        listener(PlayerEngineEvent.Ready(current.duration.coerceAtLeast(0)))
                        emitPosition(current)
                    }
                    Player.STATE_ENDED -> {
                        emitPosition(current)
                        listener(PlayerEngineEvent.Ended)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === current) listener(PlayerEngineEvent.Error(error.message ?: "Media3 playback failed"))
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== current) return
                handler.removeCallbacks(positionTicker)
                if (isPlaying) handler.post(positionTicker)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player === current) emitPosition(current)
            }
        })
    }

    override fun setEventListener(listener: (PlayerEngineEvent) -> Unit) { this.listener = listener }

    override fun load(localPath: String) = onMain {
        val current = player ?: createPlayer().also { player = it }
        current.pause()
        current.setMediaItem(MediaItem.fromUri(localPath))
        current.prepare()
    }

    override fun play() = onMain { player?.play() }
    override fun pause() = onMain { player?.pause() }
    override fun seekTo(positionMs: Long) = onMain { player?.seekTo(positionMs) }
    override fun setPlaybackSpeed(speed: Float) = onMain { player?.setPlaybackSpeed(speed) }

    override fun release() = onMain {
        handler.removeCallbacks(positionTicker)
        val old = player
        player = null
        old?.release()
    }

    private fun emitPosition(current: ExoPlayer) {
        listener(PlayerEngineEvent.Position(current.currentPosition, current.duration.coerceAtLeast(0), current.bufferedPosition.coerceAtLeast(0)))
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
}
