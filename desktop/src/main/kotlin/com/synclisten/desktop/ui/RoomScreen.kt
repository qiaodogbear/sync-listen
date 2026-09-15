package com.synclisten.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.synclisten.desktop.DesktopAppState
import com.synclisten.desktop.Screen
import com.synclisten.desktop.ui.component.EmptyState
import com.synclisten.desktop.ui.component.MemberAvatar
import com.synclisten.desktop.ui.theme.OnlineGreen
import com.synclisten.desktop.ui.theme.OfflineGrey
import com.synclisten.shared.domain.model.MemberRole
import com.synclisten.shared.domain.model.PlaybackState
import com.synclisten.shared.domain.model.Track
import com.synclisten.shared.playback.PlayerStatus
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun RoomScreen(appState: DesktopAppState) {
    val snapshot = appState.roomSnapshot ?: return
    val playlist = snapshot.playlist
    val playbackState = snapshot.playbackState
    val members = snapshot.members
    val room = snapshot.room
    val playerState by appState.playerController.state.collectAsState()
    val canControl = appState.canControlPlayback()
    val isHost = appState.isHost()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showRoleMenu by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        appState.roomError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        // --- Top Bar ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            room.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                room.roomCode,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        "${members.count { it.connected }} 人在线 · ${playlist.size} 首歌",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { appState.leaveRoom() }) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("退出")
                }
            }
        }

        // --- Member List ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                members.forEach { member ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(56.dp),
                    ) {
                        Box {
                            MemberAvatar(
                                emoji = "",
                                displayName = member.displayName,
                                size = 36.dp,
                                modifier = if (isHost && member.userId != appState.member?.userId) {
                                    Modifier.clickable { showRoleMenu = member.userId }
                                } else Modifier,
                            )
                            // Online indicator dot
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (member.connected) OnlineGreen else OfflineGrey)
                                    .align(Alignment.BottomEnd),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            member.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Role badge
                        when (member.role) {
                            MemberRole.HOST -> Text("👑", style = MaterialTheme.typography.labelSmall)
                            MemberRole.ADMIN -> Text("⭐", style = MaterialTheme.typography.labelSmall)
                            MemberRole.MEMBER -> {}
                        }
                    }

                    // Role dropdown menu
                    DropdownMenu(
                        expanded = showRoleMenu == member.userId,
                        onDismissRequest = { showRoleMenu = null },
                    ) {
                        if (member.role == MemberRole.MEMBER) {
                            DropdownMenuItem(
                                text = { Text("⭐ 任命为管理员") },
                                onClick = {
                                    appState.promoteToAdmin(member.userId)
                                    showRoleMenu = null
                                },
                            )
                        } else if (member.role == MemberRole.ADMIN) {
                            DropdownMenuItem(
                                text = { Text("撤销管理员") },
                                onClick = {
                                    appState.demoteFromAdmin(member.userId)
                                    showRoleMenu = null
                                },
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // --- Playlist ---
        if (playlist.isEmpty()) {
            EmptyState(
                icon = "🎵",
                title = "播放列表为空",
                description = "点击下方上传按钮，添加歌曲开始同步听歌",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(playlist, key = { _, t -> t.trackId }) { index, track ->
                    val isCurrentTrack = playbackState.trackId == track.trackId
                    val isPlaying = isCurrentTrack && playbackState.isPlaying

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrentTrack)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Play indicator or track number
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isCurrentTrack && isPlaying) {
                                    Text("🔊", style = MaterialTheme.typography.titleMedium)
                                } else if (isCurrentTrack) {
                                    Text("⏸", style = MaterialTheme.typography.titleMedium)
                                } else {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isCurrentTrack) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    track.artist ?: "未知艺术家",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // Duration
                            Text(
                                formatDuration(track.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // Reorder buttons
                            if (canControl) {
                                IconButton(
                                    onClick = {
                                        val reordered = playlist.toMutableList()
                                        if (index > 0) {
                                            reordered.removeAt(index)
                                            reordered.add(index - 1, track)
                                            appState.reorderPlaylist(reordered.map { it.trackId })
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = "上移",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val reordered = playlist.toMutableList()
                                        if (index < playlist.size - 1) {
                                            reordered.removeAt(index)
                                            reordered.add(index + 1, track)
                                            appState.reorderPlaylist(reordered.map { it.trackId })
                                        }
                                    },
                                    enabled = index < playlist.size - 1,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "下移",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            // Delete button
                            IconButton(
                                onClick = { showDeleteDialog = track.trackId },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Delete confirmation dialog
        showDeleteDialog?.let { trackId ->
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text("确认删除") },
                text = { Text("确定要从播放列表中移除这首歌吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            appState.removeTrack(trackId)
                            showDeleteDialog = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = null }) {
                        Text("取消")
                    }
                },
            )
        }

        HorizontalDivider()

        // --- Playback Controls ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Current track info
                if (playbackState.trackId != null) {
                    val currentTrack = playlist.find { it.trackId == playbackState.trackId }
                    Text(
                        currentTrack?.title ?: "未知曲目",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        currentTrack?.artist ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "未在播放",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Player state
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatDuration(playbackState.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.width(8.dp))

                    // Progress bar — use local player state for real-time position
                    val currentTrack = playlist.find { it.trackId == playbackState.trackId }
                    val displayPosition = if (playerState.trackId == currentTrack?.trackId && playerState.durationMs > 0)
                        playerState.positionMs else playbackState.positionMs
                    Slider(
                        value = if (currentTrack != null && currentTrack.durationMs > 0)
                            (displayPosition.toFloat() / currentTrack.durationMs).coerceIn(0f, 1f)
                        else 0f,
                        onValueChange = { fraction ->
                            val pos = currentTrack?.let {
                                (fraction * it.durationMs).toLong()
                            } ?: 0
                            appState.seekTo(pos)
                        },
                        enabled = canControl && currentTrack != null,
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        formatDuration(currentTrack?.durationMs ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Control buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Upload button
                    OutlinedButton(
                        onClick = {
                            uploadAudioFile(appState)
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上传")
                    }

                    // Previous
                    IconButton(
                        onClick = { appState.previousTrack() },
                        enabled = canControl && playlist.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "上一首")
                    }

                    // Play/Pause
                    FilledIconButton(
                        onClick = {
                            if (playbackState.isPlaying) {
                                appState.pausePlayback()
                            } else {
                                val tid = playbackState.trackId ?: playlist.firstOrNull()?.trackId
                                if (tid != null) appState.playTrack(tid)
                            }
                        },
                        enabled = canControl && playlist.isNotEmpty(),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    // Next
                    IconButton(
                        onClick = { appState.nextTrack() },
                        enabled = canControl && playlist.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "下一首")
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

/**
 * Opens a native file chooser dialog, then uploads selected audio files.
 */
private fun uploadAudioFile(appState: DesktopAppState) {
    val chooser = JFileChooser().apply {
        dialogTitle = "选择音频文件"
        isMultiSelectionEnabled = true
        fileFilter = FileNameExtensionFilter(
            "音频文件 (*.mp3, *.flac, *.ogg, *.wav, *.aac, *.m4a, *.opus, *.wma)",
            "mp3", "flac", "ogg", "wav", "aac", "m4a", "opus", "wma",
        )
    }

    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        val files: Array<File> = chooser.selectedFiles
        val roomId = appState.roomSnapshot?.room?.roomId ?: return
        val identity = appState.getIdentity()
        val serverUrl = appState.serverUrl

        // Upload in background thread
        Thread {
            val okHttp = appState.okHttpClient

            files.forEach { file ->
                try {
                    val fileName = file.name
                    val extension = fileName.substringAfterLast('.', "").lowercase()
                    val allowed = setOf("mp3", "flac", "ogg", "wav", "aac", "m4a", "opus", "wma")
                    if (extension !in allowed) {
                        println("Skipping unsupported file: $fileName")
                        return@forEach
                    }

                    // Compute SHA-256
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    require(file.length() in 1..(500L * 1024 * 1024)) { "文件必须小于 500 MB 且不能为空" }
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    val mediaType = "audio/$extension".toMediaTypeOrNull()
                        ?: "application/octet-stream".toMediaTypeOrNull()!!
                    val contentBody = file.asRequestBody(mediaType)

                    val body = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("title", file.nameWithoutExtension)
                        .addFormDataPart("artist", "")
                        .addFormDataPart("durationMs", "0")
                        .addFormDataPart("fileHash", hash)
                        .addFormDataPart("uploaderId", identity.userId)
                        .addFormDataPart("uploaderName", identity.displayName)
                        .addFormDataPart("file", fileName, contentBody)
                        .build()

                    val request = okhttp3.Request.Builder()
                        .url("${serverUrl.trimEnd('/')}/api/rooms/$roomId/tracks")
                        .post(body)
                        .build()

                    val response = okHttp.newCall(request).execute()
                    if (!response.isSuccessful) javax.swing.SwingUtilities.invokeLater { appState.roomError = "上传失败：$fileName (HTTP ${response.code})" }
                    response.close()
                } catch (e: Exception) {
                    javax.swing.SwingUtilities.invokeLater { appState.roomError = "上传失败：${file.name}，${e.message}" }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
}
