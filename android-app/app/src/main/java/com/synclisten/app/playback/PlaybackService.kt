package com.synclisten.app.playback

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.synclisten.app.MainActivity
import com.synclisten.app.ui.HomeState
import com.synclisten.app.ui.RoomSessionController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

@AndroidEntryPoint
class PlaybackService : Service() {
    @Inject lateinit var session: RoomSessionController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: View? = null
    private var title: TextView? = null
    private var play: Button? = null
    private var next: Button? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "房间后台播放", NotificationManager.IMPORTANCE_LOW))
        startForeground(701, notification())
        running.value = true
        scope.launch {
            combine(session.home, session.player, session.snapshot) { home, _, _ ->
                listOf(home, trackTitle(), session.canControl(), session.snapshot.value?.playbackState?.isPlaying)
            }.distinctUntilChanged().collect {
                if (it.first() !is HomeState.InRoom) { stopSelf(); return@collect }
                getSystemService(NotificationManager::class.java).notify(701, notification())
                updateOverlay()
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> session.togglePlayback()
            NEXT -> session.hostNext()
            LEAVE -> session.leave { stopSelf() }
            SHOW_OVERLAY -> if (Settings.canDrawOverlays(this)) showOverlay()
            HIDE_OVERLAY -> removeOverlay()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val home = session.home.value as? HomeState.InRoom
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(trackTitle())
            .setContentText(home?.room?.name ?: "正在准备房间播放")
            .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (session.canControl()) {
            builder.addAction(0, if (session.snapshot.value?.playbackState?.isPlaying == true) "暂停" else "播放", action(TOGGLE, 1))
                .addAction(0, "下一首", action(NEXT, 2))
        }
        builder.addAction(0, if (home?.hostedLocally == true) "结束房间" else "离开房间", action(LEAVE, 3))
        return builder.build()
    }

    private fun trackTitle(): String = session.snapshot.value?.playlist?.firstOrNull {
        it.trackId == session.player.value.trackId
    }?.title ?: "Sync Listen"
    private fun action(value: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, PlaybackService::class.java).setAction(value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (260 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (120 * density).toInt() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(35, 29, 51)); cornerRadius = 16 * density
            }
        }
        title = object : TextView(this) {
            override fun performClick(): Boolean {
                super.performClick()
                openApp().send()
                return true
            }
        }.apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(10, 16, 10, 16)
            contentDescription = "拖动移动，轻点返回房间"
            var initialX = 0
            var initialY = 0
            var downX = 0f
            var downY = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        downX = event.rawX; downY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (initialX + event.rawX - downX).toInt().coerceIn(0,
                            (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                        params.y = (initialY + event.rawY - downY).toInt().coerceIn(0,
                            (resources.displayMetrics.heightPixels - panel.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(panel, params) }.onFailure { removeOverlay() }
                    }
                    MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.rawX - downX) < 8 * density &&
                        kotlin.math.abs(event.rawY - downY) < 8 * density) performClick()
                }
                true
            }
        }
        panel.addView(title)
        val controls = LinearLayout(this)
        play = Button(this).apply { setOnClickListener { session.togglePlayback() } }
        next = Button(this).apply { text = "下一首"; setOnClickListener { session.hostNext() } }
        controls.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(controls)
        val links = LinearLayout(this)
        links.addView(Button(this).apply { text = "返回房间"; setOnClickListener { openApp().send() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        links.addView(Button(this).apply { text = "关闭窗口"; setOnClickListener { removeOverlay() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(links)
        overlay = panel
        runCatching { wm.addView(panel, params) }.onSuccess {
            overlayVisible.value = true
            updateOverlay()
        }.onFailure { removeOverlay() }
    }

    private fun updateOverlay() {
        if (!Settings.canDrawOverlays(this)) { removeOverlay(); return }
        title?.text = trackTitle()
        play?.text = if (session.snapshot.value?.playbackState?.isPlaying == true) "暂停" else "播放"
        play?.isEnabled = session.canControl()
        next?.isEnabled = session.canControl()
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null; title = null; play = null; next = null
        overlayVisible.value = false
    }
    override fun onDestroy() {
        removeOverlay()
        running.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "room-playback"
        private const val TOGGLE = "playback.toggle"
        private const val NEXT = "playback.next"
        private const val LEAVE = "playback.leave"
        const val SHOW_OVERLAY = "playback.overlay"
        const val HIDE_OVERLAY = "playback.hide-overlay"
        val running = MutableStateFlow(false)
        val overlayVisible = MutableStateFlow(false)
        fun start(context: Context, overlay: Boolean = false): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)
                .setAction(if (overlay) SHOW_OVERLAY else "playback.start"))
        }.isSuccess
    }
}
