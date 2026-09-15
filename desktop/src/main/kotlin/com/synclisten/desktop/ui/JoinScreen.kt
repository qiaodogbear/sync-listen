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
fun JoinScreen(appState: DesktopAppState) {
    val settings by appState.getSettingsFlow().collectAsState(initial = AppSettings())
    var displayName by remember { mutableStateOf("") }
    var roomCode by remember { mutableStateOf("") }
    var serverUrlInput by remember { mutableStateOf(settings.serverUrl) }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { appState.goBack() }) { Text("← 返回") }
            Text("加入房间", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(32.dp))

        Text("🎶", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text("输入房间信息加入好友的听歌房间",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("你的昵称") },
            singleLine = true,
            modifier = Modifier.width(350.dp),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = roomCode,
            onValueChange = { roomCode = it.uppercase().take(6) },
            label = { Text("房间码") },
            placeholder = { Text("6 位房间码，如 ABC123") },
            singleLine = true,
            modifier = Modifier.width(350.dp),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = { serverUrlInput = it },
            label = { Text("服务器地址") },
            placeholder = { Text("例如: http://192.168.1.10:38571") },
            singleLine = true,
            modifier = Modifier.width(350.dp),
        )

        Spacer(Modifier.height(24.dp))

        if (appState.roomError != null) {
            Card(
                modifier = Modifier.width(350.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Text(
                    appState.roomError ?: "",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (appState.roomError == null && appState.roomSnapshot != null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("加入成功，进入房间...", style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(
                onClick = {
                    appState.roomError = null
                    scope.launch {
                        appState.settings.update(displayName = displayName)
                    }
                    appState.joinRoom(serverUrlInput, roomCode.trim(), displayName)
                },
                enabled = !appState.busy && roomCode.trim().length == 6 && displayName.isNotBlank() && serverUrlInput.isNotBlank(),
                modifier = Modifier.width(350.dp).height(48.dp),
            ) {
                Text("加入房间", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("请输入房主分享的地址。本软件不提供默认公共云服务器。",
            style = MaterialTheme.typography.bodySmall)
    }
}
