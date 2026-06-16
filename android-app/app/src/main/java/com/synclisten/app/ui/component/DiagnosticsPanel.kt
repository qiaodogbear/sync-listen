package com.synclisten.app.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsPanel(
    webSocketStatus: String,
    serverOffsetMs: Long,
    rttMs: Long,
    expectedPositionMs: Long,
    syncErrorMs: Long,
    playbackSpeed: Float,
    downloadQueueSize: Int,
    cacheEntries: Int,
    cacheBytes: Long,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (expanded) "▼ 诊断信息" else "▶ 诊断信息")
    }

    AnimatedVisibility(visible = expanded) {
        Column(modifier = Modifier.padding(8.dp)) {
            DiagRow("WebSocket", webSocketStatus)
            DiagRow("服务器时钟偏移", "${serverOffsetMs}ms")
            DiagRow("RTT", "${rttMs}ms")
            DiagRow("期望播放位置", "${expectedPositionMs}ms")
            DiagRow("同步误差", "${syncErrorMs}ms")
            DiagRow("播放速度", "${playbackSpeed}x")
            DiagRow("下载队列", "$downloadQueueSize 个")
            DiagRow("缓存", "$cacheEntries 首 / $cacheBytes bytes")
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Text(
        "$label：$value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
