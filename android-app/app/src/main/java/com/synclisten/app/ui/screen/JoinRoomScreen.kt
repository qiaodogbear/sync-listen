package com.synclisten.app.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.synclisten.app.ui.HomeState
import com.synclisten.app.ui.HomeViewModel
import com.synclisten.app.util.ConnectivityProbe
import com.synclisten.app.util.ProbeResult
import com.synclisten.app.util.UnreachableReason
import kotlinx.coroutines.launch

@Composable
fun JoinRoomScreen(
    onBack: () -> Unit,
    onJoinedRoom: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    DisposableEffect(Unit) { onDispose { viewModel.stopBleScan() } }
    LaunchedEffect(state) {
        if (state is HomeState.InRoom) onJoinedRoom()
    }
    val bleState by viewModel.bleState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val userSettings by viewModel.settings.collectAsState(initial = com.synclisten.app.data.AppSettings())
    var roomCode by remember { mutableStateOf("") }
    var hostAddress by remember { mutableStateOf("") }
    var advancedExpanded by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var probeResult by remember { mutableStateOf<ProbeResult?>(null) }

    LaunchedEffect(userSettings) {
        if (displayName.isBlank() && userSettings.recentNickname.isNotBlank()) {
            displayName = userSettings.recentNickname
        }
    }
    var probing by remember { mutableStateOf(false) }

    // QR scanner
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        viewModel.acceptJoinLink(result.contents)
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("扫描 Sync Listen 邀请二维码"))
        else scope.launch { snackbar.showSnackbar("相机权限被拒绝，可使用房间码加入") }
    }
    val blePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) viewModel.startBleScan()
        else scope.launch { snackbar.showSnackbar("蓝牙权限被拒绝") }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("加入房间", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text("你的昵称") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // QR 扫描按钮
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE))
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("📷 扫描二维码加入")
            }
            Spacer(Modifier.height(16.dp))

            // 附近房间 (BLE)
            Text("附近房间", style = MaterialTheme.typography.titleMedium)
            if (bleState.invites.isEmpty()) {
                Text(if (bleState.status == com.synclisten.app.nearby.BleDiscoveryStatus.SCANNING) "正在扫描附近房间..." else (bleState.message ?: "点击刷新扫描，或使用下方房间码"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            bleState.invites.forEach { invite ->
                ElevatedCard(
                    onClick = {
                        if (invite.serverUrl != null) viewModel.joinRoom(invite.serverUrl, invite.roomCode, displayName)
                        else viewModel.joinRoom(invite.roomCode, displayName)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text("📡 ${invite.roomCode}",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            Button(onClick = {
                val missing = viewModel.requiredBlePermissions()
                if (missing.isEmpty()) viewModel.startBleScan()
                else blePermissions.launch(missing.toTypedArray())
            }, modifier = Modifier.fillMaxWidth()) {
                Text("🔄 刷新扫描")
            }
            Spacer(Modifier.height(16.dp))

            // 高级方式（折叠）
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Text(if (advancedExpanded) "▼ 高级方式" else "▶ 高级方式")
            }
            AnimatedVisibility(visible = advancedExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = roomCode, onValueChange = { roomCode = it.uppercase() },
                        label = { Text("房间码") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = hostAddress, onValueChange = { hostAddress = it },
                        label = { Text("Host 地址（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 连接测试
                    OutlinedButton(onClick = {
                        if (hostAddress.isNotBlank()) {
                            probing = true
                            scope.launch {
                                probeResult = ConnectivityProbe.probe(hostAddress)
                                probing = false
                                when (val r = probeResult) {
                                    is ProbeResult.Reachable -> snackbar.showSnackbar("✅ 连接正常")
                                    is ProbeResult.Unreachable -> snackbar.showSnackbar("❌ ${r.detail}")
                                    null -> {}
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = hostAddress.isNotBlank() && !probing) {
                        if (probing) CircularProgressIndicator(Modifier.size(16.dp))
                        else Text("连接测试")
                    }
                    // 连接失败操作按钮
                    val pr = probeResult
                    if (pr is ProbeResult.Unreachable) {
                        when (pr.reason) {
                            UnreachableReason.NETWORK_UNREACHABLE -> {
                                Text("设备可能不在同一网络", color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = {
                                    com.synclisten.app.util.SystemSettingsHelper.openWifiSettings(context)
                                }) { Text("打开 Wi-Fi 设置") }
                            }
                            UnreachableReason.CONNECTION_REFUSED -> {
                                Text("Host 已停止或端口被阻止", color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = {
                                    probing = true
                                    scope.launch {
                                        probeResult = ConnectivityProbe.probe(hostAddress)
                                        probing = false
                                    }
                                }) { Text("重新检测") }
                            }
                            else -> Text(pr.detail, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (hostAddress.isBlank()) viewModel.joinRoom(roomCode, displayName)
                        else viewModel.joinRoom(hostAddress, roomCode, displayName)
                    }, modifier = Modifier.fillMaxWidth(),
                        enabled = displayName.isNotBlank() && roomCode.length == 6 && state !is HomeState.Loading) {
                        Text("加入")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            (state as? HomeState.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            if (state is HomeState.Loading) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
            TextButton(onClick = onBack) { Text("← 返回首页") }
        }
    }
}
