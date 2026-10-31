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
import com.synclisten.shared.data.RepositoryResult
import com.synclisten.cloud.ui.CloudSearchDialog
import com.synclisten.cloud.ui.CloudCoverImage
import com.synclisten.cloud.ui.TrackDetailsDialog
import com.synclisten.cloud.CloudDownloader
import kotlinx.coroutines.launch
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
    var showCloud by remember { mutableStateOf(false) }
    var detailsTrackId by remember { mutableStateOf<String?>(null) }
    playlist.firstOrNull { it.trackId == detailsTrackId }?.let { track ->
        TrackDetailsDialog(
            title = track.title, artist = track.artist, durationMs = track.durationMs, cloud = track.cloud,
            isCurrent = playbackState.trackId == track.trackId,
            isPlaying = playbackState.trackId == track.trackId && playbackState.isPlaying,
            positionMs = if (playerState.trackId == track.trackId) playerState.positionMs else 0,
            canControl = canControl,
            lyrics = { cloudTrack ->
                val repository = appState.roomRepo ?: error("房间已关闭")
                when (val result = repository.cloudLyrics(room.roomId, cloudTrack)) {
                    is RepositoryResult.Success -> result.value.text
                    is RepositoryResult.Failure -> error(result.message)
                }
            },
            onPlay = { appState.playTrack(track.trackId) }, onPause = appState::pausePlayback, onSeek = appState::seekTo,
            onDismiss = { detailsTrackId = null },
        )
    }
    var cloudDownloadStatus by remember { mutableStateOf<String?>(null) }
    var cloudDownloading by remember { mutableStateOf(false) }
    val cloudScope = rememberCoroutineScope()
    CloudSearchDialog(
        visible = showCloud,
        search = { source, keyword ->
            val repository = appState.roomRepo ?: error("房间已关闭")
            when (val result = repository.searchCloud(room.roomId, source, keyword)) {
                is RepositoryResult.Success -> result.value.tracks
                is RepositoryResult.Failure -> error(result.message)
            }
        },
        resolve = { track ->
            val repository = appState.roomRepo ?: error("房间已关闭")
            when (val result = repository.resolveCloud(room.roomId, track)) {
                is RepositoryResult.Success -> result.value
                is RepositoryResult.Failure -> error(result.message)
            }
        },
        lyrics = { track ->
            val repository = appState.roomRepo ?: error("房间已关闭")
            when (val result = repository.cloudLyrics(room.roomId, track)) {
                is RepositoryResult.Success -> result.value.text
                is RepositoryResult.Failure -> error(result.message)
            }
        },
        onEnqueue = appState::addCloudTrack,
        onDownload = { initial ->
            val chooser = JFileChooser().apply {
                dialogTitle = "保存音乐"
                val extension = initial.candidates.single().format.lowercase().takeIf { it in setOf("mp3", "flac", "m4a", "ogg", "wav", "aac") } ?: "audio"
                selectedFile = File(initial.track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + "." + extension)
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val destination = chooser.selectedFile
                cloudScope.launch {
                    cloudDownloading = true
                    cloudDownloadStatus = "正在下载…"
                    try {
                        val chosen = initial.candidates.single()
                        val result = CloudDownloader().download(initial, destination, refresh = {
                            val repository = appState.roomRepo ?: error("房间已关闭")
                            when (val refreshed = repository.resolveCloud(room.roomId, initial.track)) {
                                is RepositoryResult.Success -> refreshed.value.copy(candidates = refreshed.value.candidates.filter { it.format == chosen.format && it.bitrate == chosen.bitrate })
                                is RepositoryResult.Failure -> error(refreshed.message)
                            }
                        }, accept = { it.resolvedTitle == initial.resolvedTitle && it.resolvedArtist == initial.resolvedArtist }, progress = { value ->
                            if (value.received % (256 * 1024) < 8192) cloudScope.launch {
                                val total = value.total
                                if (cloudDownloading) cloudDownloadStatus = if (total != null && total > 0) "正在下载：${value.received * 100 / total}%" else "正在下载：${value.received / 1024} KB"
                            }
                        })
                        cloudDownloadStatus = "下载完成：${result.candidate.format} ${result.candidate.bitrate ?: "未知"} kbps"
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { cloudDownloadStatus = "下载失败，请重试：${error.message ?: "未知错误"}" }
                    finally { cloudDownloading = false }
                }
            }
        },
        downloadStatus = cloudDownloadStatus,
        downloading = cloudDownloading,
        onDismiss = { showCloud = false },
    )
    var showRoleMenu by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (playerState.isCloud && playerState.status == PlayerStatus.ERROR && playbackState.isPlaying) {
            TextButton(onClick = appState::retryCloudPreparation) { Text("云播放中断，点击重试") }
        }
        playbackState.preparation?.takeUnless { it.committed }?.let { preparation ->
            val names = preparation.waiting.map { id -> members.firstOrNull { it.userId == id }?.displayName ?: id }
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(if (names.isEmpty()) "成员已准备" else "等待准备：${names.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                if (preparation.failed.isNotEmpty()) Text("部分成员解析或缓冲失败，可重试", color = MaterialTheme.colorScheme.error)
                Row {
                    if (appState.getIdentity().userId in preparation.waiting) TextButton(onClick = appState::retryCloudPreparation) { Text("重试准备") }
                    if (isHost) TextButton(onClick = appState::forceCloudPlayback) { Text(if (names.isEmpty()) "开始播放" else "房主继续") }
                }
            }
        }
        if (!showCloud && cloudDownloadStatus != null) {
            TextButton(onClick = { showCloud = true }, modifier = Modifier.fillMaxWidth()) {
                Text("云音乐 · $cloudDownloadStatus", style = MaterialTheme.typography.bodySmall)
            }
        }
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
                            // Track artwork and current playback indicator
                            Box(
                                modifier = Modifier.size(56.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CloudCoverImage(track.cloud?.track?.coverUrl.orEmpty())
                                if (isCurrentTrack && isPlaying) {
                                    Text("🔊", style = MaterialTheme.typography.titleSmall)
                                } else if (isCurrentTrack) {
                                    Text("⏸", style = MaterialTheme.typography.titleSmall)
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            TextButton(onClick = { detailsTrackId = track.trackId }, modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        track.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
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
                                    track.cloud?.let { cloud ->
                                        Text("${cloud.track.source} · ${cloud.format} ${cloud.bitrate ?: "未知"} kbps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            // Duration
                            Text(
                                formatDuration(track.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (canControl && track.status == com.synclisten.shared.domain.model.TrackStatus.READY) {
                                IconButton(onClick = { appState.playTrack(track.trackId) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "播放此曲")
                                }
                            }

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
                    if (currentTrack != null) TextButton(onClick = { detailsTrackId = currentTrack.trackId }) { Text("歌曲详情") }
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
                    val currentTrack = playlist.find { it.trackId == playbackState.trackId }
                    val position = if (playerState.trackId == currentTrack?.trackId && playerState.durationMs > 0)
                        playerState.positionMs else playbackState.positionMs
                    val displayPosition = if ((currentTrack?.durationMs ?: 0) > 0)
                        position.coerceIn(0, currentTrack!!.durationMs) else position.coerceAtLeast(0)
                    Text(
                        formatDuration(displayPosition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.width(8.dp))

                    // Progress bar — use local player state for real-time position
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
                    OutlinedButton(onClick = { showCloud = true }) { Text("云音乐") }
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
