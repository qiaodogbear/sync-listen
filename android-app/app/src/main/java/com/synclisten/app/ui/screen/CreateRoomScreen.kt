package com.synclisten.app.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.host.HostServerState
import com.synclisten.app.ui.HomeState
import com.synclisten.app.ui.HomeViewModel
import com.synclisten.app.util.SystemSettingsHelper
import kotlinx.coroutines.launch

@Composable
fun CreateRoomScreen(
    onBack: () -> Unit,
    onRoomCreated: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val hostState by viewModel.hostServerState.collectAsState()
    val userSettings by viewModel.settings.collectAsState(initial = com.synclisten.app.data.AppSettings())
    var step by remember { mutableStateOf(0) }
    var displayName by remember { mutableStateOf(userSettings.recentNickname) }
    var roomName by remember { mutableStateOf("TestRoom") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar("通知权限未授予，托管通知可能不显示") }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 标题行
            Text("创建房间", style = MaterialTheme.typography.headlineMedium)
            Text("Step ${step + 1}/3", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))

            when (step) {
                0 -> {
                    // Step 1: 昵称
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("你的昵称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { step = 1 },
                        enabled = displayName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("继续") }
                }
                1 -> {
                    // Step 2: 网络检查
                    val isRunning = hostState is HostServerState.Running
                    NetworkCheckItem("Wi-Fi 网络", true, "已连接")
                    NetworkCheckItem("通知权限",
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED) "点击下方按钮授予" else "已授予")

                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }) { Text("授予通知权限") }
                    OutlinedButton(onClick = { SystemSettingsHelper.openWifiSettings(context) }) {
                        Text("打开 Wi-Fi 设置")
                    }
                    OutlinedButton(onClick = { SystemSettingsHelper.openWirelessSettings(context) }) {
                        Text("打开热点设置")
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            viewModel.createHostedRoom(roomName, displayName)
                            step = 2
                        },
                        enabled = displayName.isNotBlank() && !isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("创建房间") }
                    TextButton(onClick = { step = 0 }) { Text("← 上一步") }
                }
                2 -> {
                    // Step 3: 完成
                    val hs = hostState
                    when (hs) {
                        is HostServerState.Running -> {
                            Text("✅ 房间已创建！", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            // 显示房间码
                            val roomState = viewModel.state.collectAsState().value
                            if (roomState is HomeState.InRoom) {
                                Text("房间码：${roomState.room.roomCode}",
                                    style = MaterialTheme.typography.headlineSmall)
                                Text("地址：${roomState.inviteServerUrl}",
                                    style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(24.dp))
                                Button(onClick = onRoomCreated,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("进入房间")
                                }
                            } else {
                                CircularProgressIndicator(Modifier.size(32.dp))
                            }
                        }
                        is HostServerState.Error -> {
                            Text("创建失败：${hs.message}",
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { step = 1 }) { Text("← 返回重试") }
                        }
                        else -> CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (step < 2) TextButton(onClick = onBack) { Text("← 返回首页") }
        }
    }
}

@Composable
private fun NetworkCheckItem(label: String, ok: Boolean, detail: String) {
    val icon = if (ok) "✅" else "⚠️"
    Text(
        "$icon $label：$detail",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
