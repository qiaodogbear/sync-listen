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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.synclisten.cloud.CloudResolution
import com.synclisten.cloud.CloudTrack
import com.synclisten.cloud.CloudTrackReference
import com.synclisten.cloud.MetadataMatch
import com.synclisten.cloud.assessResolvedTitle
import com.synclisten.cloud.assessResolvedArtists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CloudSearchDialog(
    visible: Boolean,
    search: suspend (String, String) -> List<CloudTrack>,
    resolve: suspend (CloudTrack) -> CloudResolution,
    lyrics: suspend (CloudTrack) -> String,
    onEnqueue: suspend (CloudTrackReference) -> Unit,
    onDownload: (CloudResolution) -> Unit,
    downloadStatus: String?,
    downloading: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("netease") }
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<CloudTrack>>(emptyList()) }
    var selected by remember { mutableStateOf<CloudTrack?>(null) }
    var resolution by remember { mutableStateOf<CloudResolution?>(null) }
    var lyricText by remember { mutableStateOf<String?>(null) }
    var qualityIndex by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { error = exception.message ?: "请求失败，请重试" }
            finally { busy = false }
        }
    }
    fun searchNow() {
        if (busy || query.isBlank()) return
        val term = query.trim()
        val provider = source
        tracks = emptyList(); resolution = null; lyricText = null; notice = null
        perform { tracks = search(provider, term); if (tracks.isEmpty()) error = "没有搜索结果" }
    }
    if (!visible) return
    val sources = listOf("netease" to "网易", "qq" to "QQ", "kugou" to "酷狗", "kuwo" to "酷我")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.96f).heightIn(max = 820.dp).fillMaxHeight(0.94f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selected == null) "发现音乐" else "歌曲详情", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (selected != null) TextButton(enabled = !busy, onClick = { selected = null; resolution = null; lyricText = null }) { Text("返回结果") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (selected == null) {
                    ScrollableTabRow(selectedTabIndex = sources.indexOfFirst { it.first == source }, edgePadding = 12.dp) {
                        sources.forEach { (id, label) ->
                            Tab(selected = source == id, enabled = !busy, onClick = {
                                source = id; tracks = emptyList(); resolution = null; lyricText = null; error = null; notice = null
                            }, text = { Text(label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("歌曲或歌手") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { searchNow() }), modifier = Modifier.weight(1f))
                        Button(enabled = !busy && query.isNotBlank(), onClick = { searchNow() }) { Text(if (busy) "搜索中" else "搜索") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                    if (tracks.isEmpty() && !busy && error == null) {
                        Text("搜索歌曲，查看封面、音质和歌词", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(tracks) { track ->
                            TextButton(enabled = !busy, onClick = { selected = track; error = null; notice = null }, modifier = Modifier.fillMaxWidth()) {
                                val duration = track.durationMs?.let { " · ${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}" }.orEmpty()
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    CloudCoverImage(track.coverUrl)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(track.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                        Text(track.artists.joinToString(" / ").ifEmpty { "未知歌手" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${track.album.ifEmpty { "未知专辑" }} · ${sources.firstOrNull { it.first == track.source }?.second ?: track.source}$duration", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                } else {
                    val track = selected!!
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CloudCoverImage(track.coverUrl)
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium)
                                Text(track.artists.joinToString(" / ").ifEmpty { "未知歌手" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${track.album.ifEmpty { "未知专辑" }} · ${sources.firstOrNull { it.first == track.source }?.second ?: track.source}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("音质信息：${track.availableQualities.joinToString().ifEmpty { "未知" }}", style = MaterialTheme.typography.bodySmall)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        TextButton(enabled = !busy, onClick = { perform { lyricText = lyrics(track).ifBlank { "暂无歌词" } } }) { Text("查看歌词") }
                        lyricText?.let { Text(it) }
                        Button(enabled = !busy, onClick = { resolution = null; perform { resolution = resolve(track); qualityIndex = 0 } }) { Text(if (busy) "请求中…" else "查看可用音质") }
                        resolution?.let { result ->
                            Text("选择音质：")
                            result.candidates.forEachIndexed { index, candidate ->
                                TextButton(enabled = !busy, onClick = { qualityIndex = index }) {
                                    Text("${if (index == qualityIndex) "✓ " else ""}${candidate.format} ${candidate.bitrate ?: "?"} kbps")
                                }
                            }
                            Text("解析歌曲：${result.resolvedTitle.ifEmpty { "未提供" }}；歌手：${result.resolvedArtist.ifEmpty { "未提供" }}")
                            Text(when (assessResolvedTitle(result)) {
                                MetadataMatch.MISMATCH -> "解析标题不同，可能不是所选版本"
                                MetadataMatch.UNKNOWN -> "来源未提供可核对标题，无法确认版本"
                                MetadataMatch.CONSISTENT -> "标题一致，仍需核对录音版本"
                            })
                            if (assessResolvedArtists(result) == MetadataMatch.MISMATCH) {
                                Text("解析歌手与搜索结果不同，请核对版本", color = MaterialTheme.colorScheme.error)
                            }
                            Text("请确认曲目版本，仅下载您有权使用的资源；内容可能是试听片段。")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy && result.candidates.isNotEmpty(), onClick = {
                                    val candidate = result.candidates[qualityIndex]
                                    val reference = CloudTrackReference(result.track, candidate.format, candidate.bitrate, result.resolvedTitle, result.resolvedArtist)
                                    perform { onEnqueue(reference); notice = "已加入房间" }
                                }) { Text("加入房间") }
                                Button(enabled = !busy && !downloading && result.candidates.isNotEmpty(), onClick = {
                                    onDownload(result.copy(candidates = listOf(result.candidates[qualityIndex])))
                                }) { Text("下载") }
                            }
                        }
                    }
                }
                downloadStatus?.let { Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
