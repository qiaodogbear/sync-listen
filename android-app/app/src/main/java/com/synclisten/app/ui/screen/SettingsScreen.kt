package com.synclisten.app.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.synclisten.app.ui.SettingsViewModel
import java.io.File

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(settings) {
        displayName = settings.displayName
        serverUrl = settings.serverUrl
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("调试设置", style = MaterialTheme.typography.headlineMedium)
            Text("用户 ID：${settings.userId}", style = MaterialTheme.typography.bodySmall)
            Text("最近昵称：${settings.recentNickname}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text("临时昵称") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("服务器地址") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.save(displayName, serverUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Share APK
            OutlinedButton(
                onClick = {
                    val apkFile = File(context.applicationInfo.sourceDir)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享 Sync Listen"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📤 分享应用给朋友") }

            TextButton(onClick = onBack) { Text("← 返回") }
        }
    }
}
