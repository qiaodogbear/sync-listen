package com.synclisten.app.ui.component

import android.Manifest
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.nearby.NearbyRoom
import com.synclisten.app.playback.PlaybackService
import com.synclisten.app.ui.ConvenienceViewModel
import com.synclisten.app.ui.HomeState
import kotlinx.coroutines.delay

@Composable
fun ConvenienceBar(onOpenRoom: () -> Unit, viewModel: ConvenienceViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val manager = viewModel.nearby
    val session = viewModel.session
    val home by session.home.collectAsState()
    val snapshot by session.snapshot.collectAsState()
    val player by session.player.collectAsState()
    val nearby by manager.state.collectAsState()
    val pending by manager.pending.collectAsState()
    val background by PlaybackService.running.collectAsState()
    val overlay by PlaybackService.overlayVisible.collectAsState()
    val room = home as? HomeState.InRoom
    var showNearby by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }
    var selectedRoom by remember { mutableStateOf<NearbyRoom?>(null) }
    var nickname by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Granting permission does not itself start a window; the next tap does.
        error = if (Settings.canDrawOverlays(context)) "权限已允许，请再次点击开启悬浮窗" else "未允许悬浮窗，应用内操作仍然可用"
    }
    LaunchedEffect(pending) {
        pending?.let {
            delay((it.expiresAtMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            manager.gate.expire()
        }
    }
    LaunchedEffect(home) {
        if (home is HomeState.Error) error = (home as HomeState.Error).message
        if (room == null) { showPlayback = false }
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (room != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onOpenRoom, modifier = Modifier.weight(1f)) {
                        val track = snapshot?.playlist?.firstOrNull { it.trackId == player.trackId }?.title
                        Text(track ?: room.room.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (session.canControl()) TextButton(onClick = session::togglePlayback) {
                        Text(if (snapshot?.playbackState?.isPlaying == true) "暂停" else "播放")
                    }
                    TextButton(onClick = { showPlayback = true }) { Text("小窗/后台") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showNearby = true }) {
                    Text(if (nearby.active) "附近听友 (${nearby.peers.size})" else "附近听友")
                }
                Text(if (nearby.enabled) "仅前台可见" else "附近可见已关闭",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
            }
        }
    }

    if (showNearby && selectedRoom == null && pending == null) AlertDialog(
        onDismissRequest = { showNearby = false },
        title = { Text("附近听友") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("同一 Wi-Fi 或热点内，双方开启后自动搜索。显示昵称和可加入的房间；不会自动加入。退出到后台后停止发现和接收邀请。")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("附近可见", Modifier.padding(top = 12.dp))
                    Switch(checked = nearby.enabled, onCheckedChange = manager::setEnabled)
                }
                if (nearby.active && nearby.peers.isEmpty()) Text("正在搜索。路由器客户端隔离、模拟器 NAT 或热点可能阻止发现，可继续使用二维码。")
                nearby.peers.forEach { peer ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(peer.profile.name, style = MaterialTheme.typography.titleSmall)
                    Text(peer.profile.room?.let { "房间：${it.name}" } ?: "尚未加入房间")
                    Row {
                        if (peer.profile.room != null) TextButton(
                            enabled = room == null && home !is HomeState.Loading,
                            onClick = { selectedRoom = peer.profile.room; nickname = manager.currentProfile().name },
                        ) { Text("便捷加入") }
                        if (room != null) TextButton(
                            enabled = peer.profile.room == null,
                            onClick = { manager.invite(peer) },
                        ) { Text("邀请加入") }
                    }
                }
                nearby.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onClick = { showNearby = false }) { Text("完成") } },
    )

    val proposed = pending?.invitation?.room ?: selectedRoom
    if (proposed != null) AlertDialog(
        onDismissRequest = { manager.dismissInvitation(); selectedRoom = null },
        title = { Text(pending?.let { "${it.invitation.sender} 邀请你" } ?: "确认加入") },
        text = {
            Column {
                Text("房间：${proposed.name}\n房间码：${proposed.code}\n地址：${proposed.serverUrl}")
                Text("附近昵称未经身份认证，请确认是你信任的人。")
                if (pending != null) Text("邀请 60 秒后失效。")
                if (room != null) Text("请先离开当前房间。")
                OutlinedTextField(value = nickname, onValueChange = { nickname = it.take(40) }, label = { Text("你的昵称") })
            }
        },
        confirmButton = { TextButton(
            enabled = room == null && home !is HomeState.Loading && nickname.isNotBlank(),
            onClick = {
                val accepted = if (pending != null) manager.gate.take()?.room else selectedRoom
                selectedRoom = null
                showNearby = false
                accepted?.let { viewModel.join(it, nickname) }
            },
        ) { Text("确认加入") } },
        dismissButton = { TextButton(onClick = { manager.dismissInvitation(); selectedRoom = null }) { Text("拒绝") } },
    )

    if (showPlayback && room != null) AlertDialog(
        onDismissRequest = { showPlayback = false; error = null },
        title = { Text("小窗与后台播放") },
        text = {
            Column {
                Text("应用内迷你栏无需权限。开启后台播放后显示常驻通知；悬浮窗另需系统授权。关闭窗口不会退出房间。")
                Text(if (background) "后台播放服务已开启" else "后台播放服务未开启")
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    if (!PlaybackService.start(context)) error = "服务启动失败，请保持应用前台后重试"
                }) { Text("开启后台播放") }
                OutlinedButton(onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        runCatching {
                            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                ("package:" + context.packageName).toUri()))
                        }.onFailure { error = "无法打开授权页面，请在系统设置中允许悬浮窗" }
                    } else if (!PlaybackService.start(context, overlay = true)) error = "悬浮窗服务启动失败"
                }) { Text(if (overlay) "悬浮窗已开启" else "开启跨应用悬浮窗") }
                if (overlay) TextButton(onClick = {
                    context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.HIDE_OVERLAY))
                }) { Text("关闭悬浮窗") }
                if (background) TextButton(onClick = {
                    context.stopService(Intent(context, PlaybackService::class.java))
                }) { Text("关闭后台服务和悬浮窗") }
                error?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = { showPlayback = false; error = null }) { Text("完成") } },
    )
    if (error != null && !showPlayback) AlertDialog(
        onDismissRequest = { error = null }, title = { Text("提示") }, text = { Text(error.orEmpty()) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
    )
}
