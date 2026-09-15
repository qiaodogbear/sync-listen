package com.synclisten.app.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.data.AVATAR_EMOJIS
import com.synclisten.app.ui.SettingsViewModel
import com.synclisten.app.ui.component.MemberAvatar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shareError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        displayName = settings.displayName
        serverUrl = settings.serverUrl
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("设置", style = MaterialTheme.typography.headlineMedium)

            // 头像
            Spacer(Modifier.height(16.dp))
            MemberAvatar(
                emoji = settings.avatarEmoji,
                displayName = settings.displayName.ifBlank { "?" },
                size = 64.dp,
                onClick = { showEmojiPicker = !showEmojiPicker },
            )
            if (showEmojiPicker) {
                Spacer(Modifier.height(8.dp))
                Text("选择头像", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    AVATAR_EMOJIS.forEach { emoji ->
                        TextButton(onClick = {
                            viewModel.saveAvatar(emoji)
                            showEmojiPicker = false
                        }) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("用户 ID：${settings.userId}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text("临时昵称") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                enabled = !viewModel.sessionActive,
                label = { Text("服务器地址（云端或局域网）") }, modifier = Modifier.fillMaxWidth(),
            )
            if (viewModel.sessionActive) Text("房间连接期间不能修改服务器地址。", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { viewModel.save(displayName, serverUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            val apkFile = withContext(Dispatchers.IO) {
                                val directory = File(context.cacheDir, "shared-apk").apply { mkdirs() }
                                File(context.applicationInfo.sourceDir).copyTo(File(directory, "SyncListen.apk"), overwrite = true)
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.android.package-archive"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "分享 Sync Listen"))
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            shareError = "分享失败，请从 GitHub Release 下载 APK。"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📤 分享应用给朋友") }

            shareError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onBack) { Text("← 返回") }
        }
    }
}
