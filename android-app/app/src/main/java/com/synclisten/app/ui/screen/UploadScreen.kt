package com.synclisten.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.transfer.BatchItemState
import com.synclisten.app.transfer.BatchUploadItem
import com.synclisten.app.transfer.UploadState
import com.synclisten.app.ui.UploadSelectionState
import com.synclisten.app.ui.UploadViewModel

private val ALL_AUDIO_MIME = arrayOf(
    "audio/mpeg", "audio/mp3",
    "audio/flac", "audio/x-flac",
    "audio/ogg",
    "audio/aac",
    "audio/wav", "audio/x-wav",
    "audio/opus",
    "audio/mp4",
    "audio/x-ms-wma",
)

@Composable
fun UploadScreen(
    onBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val upload by viewModel.upload.collectAsState()
    val batch by viewModel.batch.collectAsState()
    val busy = state == UploadSelectionState.Reading || upload.state == UploadState.Uploading ||
        batch.files.any { it.state in setOf(BatchItemState.Pending, BatchItemState.Inspecting, BatchItemState.Uploading) }

    // Single file launcher (kept for backward compatibility)
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.select(it)
    }

    // Multi-file launcher
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) viewModel.selectMultiple(it)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("上传音频", style = MaterialTheme.typography.headlineMedium)
            Text(
                "支持 MP3 / FLAC / OGG / AAC / WAV / Opus / M4A / WMA",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // Upload buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { multiLauncher.launch(ALL_AUDIO_MIME) }, enabled = !busy) {
                    Text("批量选择")
                }
                OutlinedButton(onClick = { singleLauncher.launch(ALL_AUDIO_MIME) }, enabled = !busy) {
                    Text("选择单个")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Batch list
            if (batch.files.isNotEmpty()) {
                Text(
                    "已选 ${batch.files.size} 个文件 · 完成 ${batch.completedCount}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (batch.files.any { it.state == BatchItemState.Ready } &&
                    batch.completedCount < batch.files.count { it.state != BatchItemState.Failed }
                ) {
                    Button(
                        onClick = viewModel::uploadAll,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("上传全部 (${batch.files.count { it.state == BatchItemState.Ready }})")
                    }
                }
                if (batch.completedCount > 0 && batch.files.size > 0) {
                    LinearProgressIndicator(
                        progress = {
                            batch.completedCount.toFloat() / batch.files.count().coerceAtLeast(1)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    itemsIndexed(batch.files) { _, item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = when (item.state) {
                                    BatchItemState.Done ->
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    BatchItemState.Failed ->
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                    else -> MaterialTheme.colorScheme.surface
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.fileName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when (item.state) {
                                            BatchItemState.Pending -> "等待中"
                                            BatchItemState.Inspecting -> "检查中..."
                                            BatchItemState.Ready -> "就绪 · ${item.fileSize / 1024 / 1024}MB"
                                            BatchItemState.Uploading -> "上传中..."
                                            BatchItemState.Done -> "✅ 完成"
                                            BatchItemState.Failed -> "❌ ${item.error ?: "失败"}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (item.state == BatchItemState.Inspecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                // Single file mode
                when (val current = state) {
                    UploadSelectionState.Empty -> Text("尚未选择文件")
                    UploadSelectionState.Reading -> CircularProgressIndicator()
                    UploadSelectionState.Cancelled -> Text("已取消选择")
                    is UploadSelectionState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
                    is UploadSelectionState.Ready -> {
                        val file = current.result.file
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(file.title, style = MaterialTheme.typography.titleMedium)
                                Text(file.artist ?: "未知艺术家")
                                Text("${file.fileName} · ${file.fileSize / 1024 / 1024}MB")
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = viewModel::upload,
                                    enabled = upload.state !is UploadState.Uploading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        when (upload.state) {
                                            is UploadState.Failed -> "重试上传"
                                            is UploadState.Success -> "已上传 ✅"
                                            else -> "上传"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                when (val current = upload.state) {
                    UploadState.Idle -> Unit
                    UploadState.Uploading -> {
                        LinearProgressIndicator(
                            progress = { upload.progress.toFloat() / 100f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                        Text("上传进度：${upload.progress}%")
                    }
                    UploadState.AlreadyRunning -> Text("相同文件正在上传")
                    is UploadState.Success -> Text(
                        if (current.result.deduplicated) "秒传完成 ✅" else "上传完成 ✅",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is UploadState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("返回") }
        }
    }
}
