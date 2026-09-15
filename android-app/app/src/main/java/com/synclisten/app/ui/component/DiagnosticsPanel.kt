package com.synclisten.app.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.synclisten.app.playback.AudioFocusState
import com.synclisten.app.playback.PlayerStatus

@Composable
fun DiagnosticsPanel(
    webSocketStatus: String,
    serverOffsetMs: Long,
    rttMs: Long,
    serverUrl: String,
    localIp: String,
    playerStatus: String,
    currentTrack: String?,
    localFilePath: String?,
    fileExists: Boolean,
    bufferedMs: Long,
    durationMs: Long,
    audioFocus: String,
    positionMs: Long,
    expectedPositionMs: Long,
    syncErrorMs: Long,
    playbackSpeed: Float,
    downloadQueueSize: Int,
    cacheEntries: Int,
    cacheBytes: Long,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    OutlinedButton(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) { Text(if (expanded) "▼ 诊断信息" else "▶ 诊断信息") }

    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            // 第 1 段：连接状态
            SectionHeader("连接状态")
            DiagRow("WebSocket", webSocketStatus)
            DiagRow("RTT", "${rttMs}ms")
            DiagRow("服务器时钟偏移", "${serverOffsetMs}ms")
            DiagRow("服务器地址", serverUrl)
            DiagRow("本地 IP", localIp)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 第 2 段：音频状态
            SectionHeader("音频状态")
            DiagRow("播放器", playerStatus)
            DiagRow("当前曲目", currentTrack ?: "无")
            DiagRow("本地文件", localFilePath?.substringAfterLast('/') ?: "无")
            DiagRow("文件存在", if (fileExists) "是" else "否")
            DiagRow("缓冲进度", if (durationMs > 0) "${bufferedMs * 100 / durationMs}% (${bufferedMs / 1000}s / ${durationMs / 1000}s)" else "未知")
            DiagRow("音频焦点", audioFocus)

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 第 3 段：同步状态
            SectionHeader("同步状态")
            DiagRow("播放位置", "%d.%03ds".format(positionMs / 1000, positionMs % 1000))
            DiagRow("上次 SYNC 期望位置", "%d.%03ds".format(expectedPositionMs / 1000, expectedPositionMs % 1000))
            DiagRow("上次同步误差", "${syncErrorMs}ms ${when {
                kotlin.math.abs(syncErrorMs) < 80 -> "(正常)"
                kotlin.math.abs(syncErrorMs) < 300 -> "(微调中)"
                else -> "(需修正)"
            }}")
            DiagRow("播放速度", "${playbackSpeed}x")
            DiagRow("最近下载计划", "$downloadQueueSize 首")
            DiagRow("缓存", "$cacheEntries 首 · ${cacheBytes / 1024} KB")

            // 复制按钮
            Button(
                onClick = {
                    val report = buildString {
                        appendLine("=== Sync Listen 诊断报告 ===")
                        appendLine("WebSocket: $webSocketStatus | RTT: ${rttMs}ms | 时钟偏移: ${serverOffsetMs}ms")
                        appendLine("服务器: $serverUrl | 本地IP: $localIp")
                        appendLine("播放器: $playerStatus | 曲目: ${currentTrack ?: "无"}")
                        appendLine("文件: ${localFilePath ?: "无"} (${if (fileExists) "存在" else "缺失"})")
                        appendLine("缓冲: ${bufferedMs}ms / ${durationMs}ms | 焦点: $audioFocus")
                        appendLine("同步误差: ${syncErrorMs}ms | 速度: ${playbackSpeed}x | 队列: $downloadQueueSize")
                        appendLine("缓存: $cacheEntries 首 | $cacheBytes bytes")
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("诊断报告", report))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📋 复制诊断信息") }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun DiagRow(label: String, value: String) {
    Text(
        "$label：$value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
