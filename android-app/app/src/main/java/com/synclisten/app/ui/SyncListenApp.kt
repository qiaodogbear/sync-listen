package com.synclisten.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.synclisten.app.data.RoomConnectionState

private const val HOME_ROUTE = "home"
private const val SETTINGS_ROUTE = "settings"
private const val ROOM_ROUTE = "room"
private const val UPLOAD_ROUTE = "upload"
private const val PLAYER_ROUTE = "player"
private const val INVITE_ROUTE = "invite"

@Composable
fun SyncListenApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
            HomeScreen(
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                onEnteredRoom = { navController.navigate(ROOM_ROUTE) },
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROOM_ROUTE) {
            RoomResultScreen(
                onLeave = {
                    navController.popBackStack(HOME_ROUTE, inclusive = false)
                },
                onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                onOpenUpload = { navController.navigate(UPLOAD_ROUTE) },
                onOpenPlayer = { navController.navigate(PLAYER_ROUTE) },
                onOpenInvite = { navController.navigate(INVITE_ROUTE) },
            )
        }
        composable(UPLOAD_ROUTE) {
            UploadScreen(onBack = { navController.popBackStack() })
        }
        composable(PLAYER_ROUTE) {
            PlaceholderScreen("播放器", "将在阶段五实现", "返回") { navController.popBackStack() }
        }
        composable(INVITE_ROUTE) {
            PlaceholderScreen("邀请成员", "将在阶段六实现", "返回") { navController.popBackStack() }
        }
    }
}

@Composable
private fun UploadScreen(
    onBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.select(it)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
                Text("${file.fileName} · ${file.fileSize} bytes · ${file.durationMs} ms")
                Text("SHA-256：${file.sha256}")
            }
        }
        Button(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun HomeScreen(
    onOpenSettings: () -> Unit,
    onEnteredRoom: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var roomName by remember { mutableStateOf("") }
    var roomCode by remember { mutableStateOf("") }
    val loading = state is HomeState.Loading

    LaunchedEffect(state) {
        if (state is HomeState.InRoom) onEnteredRoom()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Sync Listen", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("临时昵称") },
            enabled = !loading,
        )
        OutlinedTextField(
            value = roomName,
            onValueChange = { roomName = it },
            label = { Text("新房间名称") },
            enabled = !loading,
        )
        Button(
            onClick = { viewModel.createRoom(roomName, displayName) },
            enabled = !loading && displayName.isNotBlank() && roomName.isNotBlank(),
        ) {
            Text("创建房间")
        }
        OutlinedTextField(
            value = roomCode,
            onValueChange = { roomCode = it.uppercase() },
            label = { Text("房间码") },
            enabled = !loading,
        )
        Button(
            onClick = { viewModel.joinRoom(roomCode, displayName) },
            enabled = !loading && displayName.isNotBlank() && roomCode.isNotBlank(),
        ) {
            Text("加入房间")
        }
        if (loading) CircularProgressIndicator()
        if (state is HomeState.Error) {
            Text((state as HomeState.Error).message, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onOpenSettings, enabled = !loading) {
            Text("调试设置")
        }
    }
}

@Composable
private fun RoomResultScreen(
    onLeave: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpload: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenInvite: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
    roomViewModel: RoomViewModel = hiltViewModel(),
) {
    val state by homeViewModel.state.collectAsState()
    val connection by roomViewModel.connection.collectAsState()
    val snapshot by roomViewModel.snapshot.collectAsState()
    val room = state as? HomeState.InRoom

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = room?.room?.name ?: "房间不可用", style = MaterialTheme.typography.headlineMedium)
        Text(text = "房间码：${room?.room?.roomCode.orEmpty()}")
        Text(text = "角色：${room?.member?.role?.name.orEmpty()}")
        Text(text = "连接：${connection.label()}")
        Text(text = "成员")
        snapshot?.members?.forEach { member ->
            Text("${member.displayName} · ${member.role.name} · ${if (member.connected) "在线" else "离线"}")
        }
        Text(text = "播放列表")
        snapshot?.playlist?.forEach { track ->
            Text("${track.title} · ${track.status.name}")
        }
        Button(onClick = onOpenUpload) { Text("上传歌曲") }
        Button(onClick = onOpenPlayer) { Text("播放器") }
        Button(onClick = onOpenInvite) { Text("邀请") }
        Button(onClick = onOpenSettings) { Text("设置") }
        Button(onClick = {
            roomViewModel.leave(onLeave)
        }) {
            Text("返回首页")
        }
    }
}

private fun RoomConnectionState.label(): String = when (this) {
    RoomConnectionState.Disconnected -> "已断开"
    RoomConnectionState.Connecting -> "连接中"
    RoomConnectionState.Connected -> "已连接"
    is RoomConnectionState.Reconnecting -> "重连中（第 $attempt 次）"
    is RoomConnectionState.Failed -> "失败：$message"
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        displayName = settings.displayName
        serverUrl = settings.serverUrl
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "调试设置", style = MaterialTheme.typography.headlineMedium)
        Text(text = "用户 ID：${settings.userId}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("临时昵称") },
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("服务器地址") },
        )
        Button(onClick = { viewModel.save(displayName, serverUrl) }) {
            Text("保存")
        }
        Button(onClick = onBack) {
            Text("返回")
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = body, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}
