package com.synclisten.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.synclisten.desktop.DesktopAppState
import com.synclisten.desktop.Screen
import com.synclisten.shared.data.AppSettings
import kotlinx.coroutines.launch

@Composable
fun CreateScreen(appState: DesktopAppState) {
    val settings by appState.getSettingsFlow().collectAsState(initial = AppSettings())
    var step by remember { mutableStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    var roomName by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        if (displayName.isBlank() && settings.recentNickname.isNotBlank()) {
            displayName = settings.recentNickname
        } else if (displayName.isBlank() && settings.displayName.isNotBlank()) {
            displayName = settings.displayName
        }
    }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { appState.goBack() }) {
                Text("← 返回")
            }
            Text("创建房间", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(32.dp))

        // Step indicator
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { i ->
                Surface(
                    modifier = Modifier.size(if (i == step) 12.dp else 8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (i <= step) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {}
            }
        }

        Spacer(Modifier.height(32.dp))

        when (step) {
            0 -> {
                Text("你的昵称", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "其他成员将看到这个名字",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("昵称") },
                    singleLine = true,
                    modifier = Modifier.width(300.dp),
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { if (displayName.isNotBlank()) step = 1 },
                    enabled = displayName.isNotBlank(),
                ) {
                    Text("下一步")
                }
            }

            1 -> {
                Text("房间名称", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("给你的房间取个名字", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("房间名") },
                    placeholder = { Text("${displayName}的音乐房间") },
                    singleLine = true,
                    modifier = Modifier.width(300.dp),
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step = 0 }) { Text("上一步") }
                    Button(
                        onClick = {
                            if (roomName.isNotBlank() && displayName.isNotBlank()) {
                                scope.launch {
                                    appState.settings.update(displayName = displayName)
                                }
                                appState.createRoom(roomName, displayName)
                                step = 2
                            }
                        },
                        enabled = roomName.isNotBlank() && displayName.isNotBlank(),
                    ) {
                        Text("创建房间")
                    }
                }
            }

            2 -> {
                if (appState.roomError != null) {
                    Text("❌ 创建失败", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(appState.roomError ?: "", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { appState.roomError = null; step = 1 }) { Text("重试") }
                } else if (appState.roomSnapshot != null) {
                    Text("✅ 房间已创建！", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("房间码", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        appState.roomSnapshot!!.room.roomCode,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("分享这个房间码给好友，他们即可加入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { appState.currentScreen = Screen.Room }) {
                        Text("进入房间")
                    }
                } else {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("正在创建房间...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
