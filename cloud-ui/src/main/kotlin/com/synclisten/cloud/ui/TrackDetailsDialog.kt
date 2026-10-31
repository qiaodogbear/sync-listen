package com.synclisten.cloud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.synclisten.cloud.CloudTrack
import com.synclisten.cloud.CloudTrackReference
import com.synclisten.cloud.CloudResolution
import com.synclisten.cloud.MetadataMatch
import com.synclisten.cloud.ParsedLyrics
import com.synclisten.cloud.assessResolvedArtists
import com.synclisten.cloud.assessResolvedTitle
import com.synclisten.cloud.currentLyricIndex
import com.synclisten.cloud.parseLyrics
import kotlinx.coroutines.CancellationException

@Composable
fun TrackDetailsDialog(
    title: String,
    artist: String?,
    durationMs: Long,
    cloud: CloudTrackReference?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    canControl: Boolean,
    lyrics: suspend (CloudTrack) -> String,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var parsed by remember(cloud?.track) { mutableStateOf<ParsedLyrics?>(null) }
    var message by remember(cloud?.track) { mutableStateOf<String?>(null) }
    var retry by remember(cloud?.track) { mutableIntStateOf(0) }
    var failed by remember(cloud?.track) { mutableStateOf(false) }
    var follow by remember(cloud?.track) { mutableStateOf(true) }
    val track = cloud?.track
    LaunchedEffect(track, retry) {
        parsed = null
        failed = false
        message = when {
            track == null -> "本地曲目暂不支持歌词"
            track.source !in setOf("netease", "kugou") -> "该来源的歌词暂不可用"
            else -> "正在获取歌词…"
        }
        if (track != null && track.source in setOf("netease", "kugou")) {
            try {
                val result = parseLyrics(lyrics(track))
                parsed = result
                message = if (result.lines.isEmpty() && result.plainText.isBlank()) "暂无歌词" else null
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                message = when {
                    error.message?.contains("没有与所选歌曲匹配的歌词") == true || error.message?.contains("No lyrics match the selected track") == true -> "没有与所选歌曲匹配的歌词"
                    else -> "歌词获取失败，请重试"
                }
                failed = true
            }
        }
    }
    val lines = parsed?.lines.orEmpty()
    val active = if (isCurrent) currentLyricIndex(lines, positionMs) else -1
    val listState = rememberLazyListState()
    LaunchedEffect(active, follow) {
        if (follow && active >= 0) listState.animateScrollToItem(active + 1)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.96f).heightIn(max = 820.dp).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.background) {
            Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("歌曲详情", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                if (isCurrent && lines.isNotEmpty()) TextButton(onClick = { follow = !follow }) {
                    Text(if (follow) "自动跟随" else "手动浏览")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CloudCoverImage(track?.coverUrl.orEmpty(), size = 96.dp)
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(artist?.takeIf { it.isNotBlank() } ?: "未知歌手", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(track?.album?.takeIf { it.isNotBlank() } ?: "未知专辑", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val duration = if (durationMs > 0) "${durationMs / 60_000}:${(durationMs / 1000 % 60).toString().padStart(2, '0')}" else "未知"
                        val source = when (track?.source) { "netease" -> "网易"; "qq" -> "QQ"; "kugou" -> "酷狗"; "kuwo" -> "酷我"; else -> track?.source }
                        Text("时长：$duration · ${if (track == null) "本地文件" else "来源：$source"}", style = MaterialTheme.typography.bodySmall)
                        if (isCurrent) Text("播放位置：${positionMs.coerceAtLeast(0) / 60_000}:${(positionMs.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall)
                        cloud?.let {
                            Text("所选音质：${it.format} ${it.bitrate?.let { rate -> "$rate kbps" } ?: "码率未知"}", style = MaterialTheme.typography.bodySmall)
                            Text("解析版本：${it.resolvedTitle.ifBlank { "未提供" }} · ${it.resolvedArtist.ifBlank { "未提供" }}", style = MaterialTheme.typography.bodySmall)
                            val metadata = CloudResolution(it.track, emptyList(), it.resolvedTitle, it.resolvedArtist, "")
                            if (assessResolvedTitle(metadata) == MetadataMatch.MISMATCH || assessResolvedArtists(metadata) == MetadataMatch.MISMATCH) {
                                Text("解析结果与搜索曲目不一致，请核对版本", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("请核对录音版本；来源可能提供试听片段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canControl) Button(onClick = if (isCurrent && isPlaying) onPause else onPlay) {
                            Text(if (isCurrent && isPlaying) "暂停" else if (isCurrent) "继续播放" else "播放此曲")
                        }
                        Text("歌词", style = MaterialTheme.typography.titleMedium)
                        if (!isCurrent && lines.isNotEmpty()) Text("播放此曲后，歌词将跟随进度", style = MaterialTheme.typography.bodySmall)
                        message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (failed) TextButton(onClick = { retry++ }) { Text("重试歌词") }
                        parsed?.plainText?.takeIf { lines.isEmpty() && it.isNotBlank() }?.let { Text(it) }
                    }
                }
                itemsIndexed(lines) { index, line ->
                    val color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    if (isCurrent && canControl) {
                        TextButton(onClick = { onSeek(line.timeMs) }, modifier = Modifier.fillMaxWidth()) {
                            Text(line.text, color = color, fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal)
                        }
                    } else {
                        Text(line.text, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), color = color,
                            fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            }
        }
    }
}
