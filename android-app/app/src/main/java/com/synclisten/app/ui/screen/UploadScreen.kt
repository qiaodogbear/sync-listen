package com.synclisten.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.transfer.UploadState
import com.synclisten.app.ui.UploadSelectionState
import com.synclisten.app.ui.UploadViewModel

@Composable
fun UploadScreen(
    onBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val upload by viewModel.upload.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.select(it)
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("选择本地音频", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { launcher.launch(arrayOf("audio/mpeg", "audio/flac", "audio/x-flac")) }) {
                Text("选择 MP3 / FLAC")
            }
            when (val current = state) {
                UploadSelectionState.Empty -> Text("尚未选择文件")
                UploadSelectionState.Reading -> CircularProgressIndicator()
                UploadSelectionState.Cancelled -> Text("已取消选择")
                is UploadSelectionState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
                is UploadSelectionState.Ready -> {
                    val file = current.result.file
                    Text(file.title)
                    Text(file.artist ?: "未知艺术家")
                    Text("${file.fileName} · ${file.fileSize} bytes")
                    Button(
                        onClick = viewModel::upload,
                        enabled = upload.state !is UploadState.Uploading,
                    ) {
                        Text(if (upload.state is UploadState.Failed) "重试上传" else "上传")
                    }
                }
            }
            when (val current = upload.state) {
                UploadState.Idle -> Unit
                UploadState.Uploading -> Text("上传进度：${upload.progress}%")
                UploadState.AlreadyRunning -> Text("相同文件正在上传")
                is UploadState.Success -> Text(if (current.result.deduplicated) "秒传完成" else "上传完成")
                is UploadState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onBack) { Text("返回") }
        }
    }
}
