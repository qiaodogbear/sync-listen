package com.synclisten.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.domain.model.TrackStatus
import com.synclisten.app.playback.PlayerStatus
import com.synclisten.app.playback.canControlPlayback
import com.synclisten.app.ui.HomeState
import com.synclisten.app.ui.HomeViewModel
import com.synclisten.app.ui.RoomViewModel
import com.synclisten.app.ui.component.DiagnosticsPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    onLeave: () -> Unit,
    onOpenUpload: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenSettings: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
    roomViewModel: RoomViewModel = hiltViewModel(),
) {
    val homeState by homeViewModel.state.collectAsState()
    val connection by roomViewModel.connection.collectAsState()
    val snapshot by roomViewModel.snapshot.collectAsState()
    val downloads by roomViewModel.downloads.collectAsState()
    val cacheSummary by roomViewModel.cacheSummary.collectAsState()
    val player by roomViewModel.player.collectAsState()
    val clock by roomViewModel.clock.collectAsState()
    val sync by roomViewModel.sync.collectAsState()
    val controlError by roomViewModel.controlError.collectAsState()
    val room = homeState as? HomeState.InRoom
    val canControl = room?.member?.role?.canControlPlayback() == true
    val currentTrack = snapshot?.playlist?.firstOrNull {
        it.trackId == (player.trackId ?: snapshot?.playbackState?.trackId)
    }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(room?.room?.name ?: "房间", style = MaterialTheme.typography.titleMedium)
                    Text("房间码：${room?.room?.roomCode.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall)
                } },
                actions = {
                    IconButton(onClick = onOpenUpload) { Text("📤") }
                    IconButton(onClick = onOpenInvite) { Text("📨") }
                    IconButton(onClick = onOpenSettings) { Text("⚙") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            // 成员行
            Text("成员", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                snapshot?.members?.forEach { member ->
                    Surface(
                        modifier = Modifier.size(36.dp).clip(CircleShape),
                        color = if (member.connected)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(member.displayName.take(1),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(member.displayName, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 播放列表
            Text("播放列表 (${snapshot?.playlist?.size ?: 0})",
                style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(snapshot?.playlist ?: emptyList()) { track ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (track.trackId == currentTrack?.trackId)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.bodyLarge)
                                Text("${track.status.name} · ${track.artist ?: "未知"}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (canControl && track.status == TrackStatus.READY) {
                                Button(onClick = { roomViewModel.hostPlay(track.trackId) }) {
                                    Text("▶")
                                }
                            }
                        }
                    }
                }
            }

            // 底部迷你播放器
            if (currentTrack != null || player.status == PlayerStatus.PLAYING || player.status == PlayerStatus.READY) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎵", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(currentTrack?.title ?: "加载中...",
                                    style = MaterialTheme.typography.bodyLarge)
                                if (player.durationMs > 0) {
                                    Text("${player.positionMs / 1000}:${(player.positionMs % 1000) / 10} / ${player.durationMs / 1000}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)
                                else 0f
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        if (canControl) {
                            Row(horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { roomViewModel.hostPlay(player.trackId ?: currentTrack?.trackId ?: "") }) {
                                    Text("▶")
                                }
                                Button(onClick = roomViewModel::hostPause) { Text("⏸") }
                                Button(onClick = { roomViewModel.hostSeek(player.positionMs + 5000) }) {
                                    Text("⏩")
                                }
                                Button(onClick = roomViewModel::hostNext) { Text("⏭") }
                            }
                        }
                        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // 诊断信息（可折叠）
            DiagnosticsPanel(
                webSocketStatus = when (connection) {
                    is com.synclisten.app.data.RoomConnectionState.Connected -> "已连接"
                    is com.synclisten.app.data.RoomConnectionState.Connecting -> "连接中"
                    is com.synclisten.app.data.RoomConnectionState.Disconnected -> "已断开"
                    is com.synclisten.app.data.RoomConnectionState.Reconnecting -> "重连中"
                    is com.synclisten.app.data.RoomConnectionState.Failed -> "失败"
                },
                serverOffsetMs = clock.serverOffsetMs,
                rttMs = clock.rttMs,
                expectedPositionMs = sync.expectedPositionMs,
                syncErrorMs = sync.syncErrorMs,
                playbackSpeed = sync.playbackSpeed,
                downloadQueueSize = downloads.queued,
                cacheEntries = cacheSummary.entries,
                cacheBytes = cacheSummary.physicalBytes,
            )

            // 操作按钮行
            Row(horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Button(onClick = {
                    roomViewModel.leave(onLeave)
                }) { Text("返回首页") }
            }
            controlError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
