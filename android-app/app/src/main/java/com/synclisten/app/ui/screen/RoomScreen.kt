package com.synclisten.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.domain.model.MemberRole
import com.synclisten.app.domain.model.TrackStatus
import com.synclisten.app.playback.PlayerStatus
import com.synclisten.app.playback.canControlPlayback
import com.synclisten.app.ui.HomeState
import com.synclisten.app.ui.HomeViewModel
import com.synclisten.app.ui.RoomViewModel
import com.synclisten.app.ui.UploadViewModel
import com.synclisten.app.transfer.BatchUploadState
import com.synclisten.app.ui.component.DiagnosticsPanel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RoomScreen(
    onLeave: () -> Unit,
    onOpenUpload: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenSettings: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
    sessionViewModel: RoomViewModel = hiltViewModel(),
    uploadViewModel: UploadViewModel = hiltViewModel(),
) {
    val roomViewModel = sessionViewModel.session
    val homeState by homeViewModel.state.collectAsState()
    val connection by roomViewModel.connection.collectAsState()
    val snapshot by roomViewModel.snapshot.collectAsState()
    val downloads by roomViewModel.downloads.collectAsState()
    val cacheSummary by roomViewModel.cacheSummary.collectAsState()
    val player by roomViewModel.player.collectAsState()
    val clock by roomViewModel.clock.collectAsState()
    val sync by roomViewModel.sync.collectAsState()
    val controlError by roomViewModel.controlError.collectAsState()
    val batchUpload by uploadViewModel.batch.collectAsState(initial = BatchUploadState())
    val room = homeState as? HomeState.InRoom
    val myRole = snapshot?.members?.firstOrNull { it.userId == room?.member?.userId }?.role ?: room?.member?.role
    val canControl = myRole?.canControlPlayback() == true
    val isHost = myRole == MemberRole.HOST
    val currentTrack = snapshot?.playlist?.firstOrNull {
        it.trackId == (player.trackId ?: snapshot?.playbackState?.trackId)
    }
    val snackbar = remember { SnackbarHostState() }

    var showRoleMenuFor by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (isHost) "结束房间？" else "离开房间？") },
            text = { Text(if (isHost) "所有成员将断开，已结束的房间不能恢复。" else "将停止本机播放。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; roomViewModel.leave(onLeave) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("取消") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(room?.room?.name ?: "房间", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "房间码：${room?.room?.roomCode.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenUpload) { Text("添加") }
                    TextButton(onClick = onOpenInvite) { Text("邀请") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            // Members row with role badges
            Text("成员", style = MaterialTheme.typography.titleMedium)
            LazyRow(modifier = Modifier.padding(vertical = 4.dp)) {
                items(snapshot?.members ?: emptyList()) { member ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(64.dp)
                            .then(
                                if (isHost && member.userId != room?.member?.userId) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { showRoleMenuFor = member.userId },
                                    )
                                } else Modifier
                            ),
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp).clip(CircleShape),
                            color = if (member.connected)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    member.displayName.take(1),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        // Role badge + name
                        Text(
                            when (member.role) {
                                MemberRole.HOST -> "👑${member.displayName}"
                                MemberRole.ADMIN -> "⭐${member.displayName}"
                                else -> member.displayName
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }

                    // Role management dropdown
                    DropdownMenu(
                        expanded = showRoleMenuFor == member.userId,
                        onDismissRequest = { showRoleMenuFor = null },
                    ) {
                        if (member.role == MemberRole.MEMBER) {
                            DropdownMenuItem(
                                text = { Text("⭐ 任命为管理员") },
                                onClick = {
                                    roomViewModel.promoteToAdmin(member.userId)
                                    showRoleMenuFor = null
                                },
                            )
                        } else if (member.role == MemberRole.ADMIN) {
                            DropdownMenuItem(
                                text = { Text("撤销管理员") },
                                onClick = {
                                    roomViewModel.demoteFromAdmin(member.userId)
                                    showRoleMenuFor = null
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Upload progress (survives navigation away from UploadScreen)
            if (batchUpload.files.isNotEmpty() && batchUpload.completedCount < batchUpload.files.size) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    ),
                    onClick = onOpenUpload,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📤", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "上传中 (${batchUpload.completedCount}/${batchUpload.files.size})",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "点击查看详情",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = {
                                val total = batchUpload.files.size.coerceAtLeast(1)
                                batchUpload.completedCount.toFloat() / total
                            },
                            modifier = Modifier.width(80.dp),
                        )
                    }
                }
            }

            // Playlist with edit controls
            Text(
                "播放列表 (${snapshot?.playlist?.size ?: 0})",
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                itemsIndexed(snapshot?.playlist ?: emptyList()) { index, track ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (track.trackId == currentTrack?.trackId)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (track.trackId == currentTrack?.trackId && player.status == PlayerStatus.PLAYING) {
                                        Text("🔊 ", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(track.title, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    "${track.artist ?: "未知"} · ${formatMs(track.durationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Play button
                            if (canControl && track.status == TrackStatus.READY) {
                                IconButton(
                                    onClick = { roomViewModel.hostPlay(track.trackId) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Text("▶", style = MaterialTheme.typography.labelLarge)
                                }
                            }

                            // Reorder buttons
                            if (canControl) {
                                if (index > 0) {
                                    IconButton(
                                        onClick = {
                                            val reordered = snapshot?.playlist?.toMutableList() ?: return@IconButton
                                            reordered.removeAt(index)
                                            reordered.add(index - 1, track)
                                            roomViewModel.reorderPlaylist(reordered.map { it.trackId })
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            contentDescription = "上移",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                if (index < (snapshot?.playlist?.size ?: 1) - 1) {
                                    IconButton(
                                        onClick = {
                                            val reordered = snapshot?.playlist?.toMutableList() ?: return@IconButton
                                            reordered.removeAt(index)
                                            reordered.add(index + 1, track)
                                            roomViewModel.reorderPlaylist(reordered.map { it.trackId })
                                        },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = "下移",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }

                            // Delete button
                            IconButton(
                                onClick = { showDeleteDialog = track.trackId },
                                enabled = canControl && track.trackId != currentTrack?.trackId,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Delete confirmation dialog
            showDeleteDialog?.let { trackId ->
                val trackToDelete = snapshot?.playlist?.find { it.trackId == trackId }
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = null },
                    title = { Text("确认删除") },
                    text = {
                        Text("确定要移除「${trackToDelete?.title ?: "这首歌"}」吗？")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                roomViewModel.removeTrack(trackId)
                                showDeleteDialog = null
                            },
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = null }) {
                            Text("取消")
                        }
                    },
                )
            }

            // Mini player
            if (currentTrack != null || player.status == PlayerStatus.PLAYING || player.status == PlayerStatus.READY) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎵", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    currentTrack?.title ?: "加载中...",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (player.durationMs > 0) {
                                    Text(
                                        "${formatMs(player.positionMs)} / ${formatMs(player.durationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (player.durationMs > 0)
                                    (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)
                                else 0f
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        if (canControl) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Button(onClick = {
                                    roomViewModel.hostPlay(
                                        player.trackId ?: currentTrack?.trackId ?: ""
                                    )
                                }) { Text("▶") }
                                Button(onClick = roomViewModel::hostPause) { Text("⏸") }
                                Button(onClick = {
                                    roomViewModel.hostSeek(player.positionMs + 5000)
                                }) { Text("⏩") }
                                Button(onClick = roomViewModel::hostNext) { Text("⏭") }
                            }
                        }
                        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // Diagnostics
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
                serverUrl = room?.inviteServerUrl
                    ?: homeViewModel.settings.collectAsState(
                        initial = com.synclisten.app.data.AppSettings()
                    ).value.serverUrl,
                localIp = "请以邀请地址为准",
                playerStatus = player.status.name,
                currentTrack = currentTrack?.title,
                localFilePath = player.localFilePath,
                fileExists = player.localFilePath?.let { java.io.File(it).exists() } ?: false,
                bufferedMs = player.bufferedMs,
                durationMs = player.durationMs,
                audioFocus = "Media3 自动管理",
                positionMs = player.positionMs,
                expectedPositionMs = sync.expectedPositionMs,
                syncErrorMs = sync.syncErrorMs,
                playbackSpeed = sync.playbackSpeed,
                downloadQueueSize = downloads.queued,
                cacheEntries = cacheSummary.entries,
                cacheBytes = cacheSummary.physicalBytes,
                clockUncertaintyMs = clock.uncertaintyMs,
                checkpointCount = sync.checkpointCount,
                correction = sync.correction,
                syncConnected = sync.connected,
            )

            // Bottom actions
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Button(onClick = { confirmLeave = true }) {
                    Text(if (isHost) "结束房间" else "离开房间")
                }
            }
            controlError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
