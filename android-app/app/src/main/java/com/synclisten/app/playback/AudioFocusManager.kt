package com.synclisten.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.synclisten.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AudioFocusState { UNKNOWN, GRANTED, LOST, LOST_TRANSIENT, REQUESTING }

@Singleton
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutableState = MutableStateFlow(AudioFocusState.UNKNOWN)
    val state: StateFlow<AudioFocusState> = mutableState

    private var request: AudioFocusRequest? = null
    var onFocusLost: (() -> Unit)? = null

    fun request(): AudioFocusState {
        mutableState.value = AudioFocusState.REQUESTING
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attr)
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                mutableState.value = AudioFocusState.LOST
                                AppLogger.debug("AudioFocus", "lost permanently")
                                onFocusLost?.invoke()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                mutableState.value = AudioFocusState.LOST_TRANSIENT
                                AppLogger.debug("AudioFocus", "lost transiently")
                                onFocusLost?.invoke()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                mutableState.value = AudioFocusState.GRANTED
                                AppLogger.debug("AudioFocus", "gained")
                            }
                        }
                    }.build()
                this.request = focusRequest
                val result = manager.requestAudioFocus(focusRequest)
                mutableState.value = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    AudioFocusState.GRANTED else AudioFocusState.LOST
            } else {
                @Suppress("DEPRECATION")
                val result = manager.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            mutableState.value = AudioFocusState.LOST
                            onFocusLost?.invoke()
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
                mutableState.value = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    AudioFocusState.GRANTED else AudioFocusState.LOST
            }
            mutableState.value
        } catch (e: Exception) {
            AppLogger.error("AudioFocus", "request failed", e)
            mutableState.value = AudioFocusState.UNKNOWN
            AudioFocusState.UNKNOWN
        }
    }

    fun abandon() {
        request?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.abandonAudioFocusRequest(it) }
        request = null
        mutableState.value = AudioFocusState.UNKNOWN
    }
}
