package com.synclisten.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.synclisten.desktop.DesktopAppState
import com.synclisten.desktop.Screen
import com.synclisten.desktop.ui.component.MemberAvatar
import com.synclisten.desktop.ui.theme.CreateCardTint
import com.synclisten.desktop.ui.theme.JoinCardTint
import com.synclisten.desktop.ui.theme.OnlineGreen
import com.synclisten.shared.data.AppSettings
import com.synclisten.shared.data.normalizeServerUrl
import com.synclisten.shared.util.ConnectivityProbe
import com.synclisten.shared.util.ProbeResult
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(appState: DesktopAppState) {
    val settings by appState.getSettingsFlow().collectAsState(initial = AppSettings())
    var serverUrlInput by remember { mutableStateOf(settings.serverUrl) }
    var checkingServer by remember { mutableStateOf(false) }
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // User identity bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(
                    emoji = settings.avatarEmoji,
                    displayName = settings.displayName,
                    size = 36.dp,
                    onClick = { appState.navigateTo(Screen.Settings) },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "你好，${settings.displayName.ifBlank { "用户" }}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        settings.userId.take(8) + "...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { appState.navigateTo(Screen.Settings) }) {
                Text("⚙ 设置")
            }
        }

        Spacer(Modifier.height(32.dp))

        // App title
        Text("🎧", style = MaterialTheme.typography.displayLarge)
        Text(
            "Sync Listen",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "多人同步听歌",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Server URL input
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("服务器地址", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("例如: http://192.168.1.10:38571") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                checkingServer = true
                                serverReachable = null
                                val normalized = normalizeServerUrl(serverUrlInput)
                                val result = ConnectivityProbe.probe(normalized)
                                serverReachable = result is ProbeResult.Reachable
                                if (serverReachable == true) {
                                    appState.settings.update(serverUrl = normalized)
                                    appState.serverUrl = normalized
                                }
                                checkingServer = false
                            }
                        },
                        enabled = !checkingServer && serverUrlInput.isNotBlank(),
                    ) {
                        Text(if (checkingServer) "检测中..." else "检测")
                    }
                }
                if (serverReachable != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (serverReachable == true) "✅ 服务器可达" else "❌ 无法连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (serverReachable == true) OnlineGreen else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Two main action cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.weight(1f),
                onClick = { appState.navigateTo(Screen.Create) },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🏠", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "创建房间",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = CreateCardTint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "创建同步听歌房间\n邀请好友加入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                onClick = { appState.navigateTo(Screen.Join) },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🎶", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "加入房间",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = JoinCardTint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "输入房间码加入\n好友的听歌房间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Render.com hint
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("☁️", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("无需默认云服务器", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "填写房主手机地址，或你自己部署的后端地址",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
