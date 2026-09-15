package com.synclisten.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.synclisten.desktop.DesktopAppState
import com.synclisten.desktop.ui.component.MemberAvatar
import com.synclisten.shared.data.AppSettings
import com.synclisten.shared.data.AVATAR_EMOJIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(appState: DesktopAppState) {
    val settings by appState.getSettingsFlow().collectAsState(initial = AppSettings())
    var displayName by remember { mutableStateOf(settings.displayName) }
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var avatarEmoji by remember { mutableStateOf(settings.avatarEmoji) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { appState.goBack() }) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(64.dp))
        }

        Spacer(Modifier.height(24.dp))

        Text("头像", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        MemberAvatar(emoji = avatarEmoji, displayName = displayName, size = 64.dp)
        Spacer(Modifier.height(12.dp))

        Text("选择一个表情", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.width(350.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AVATAR_EMOJIS.forEach { emoji ->
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = if (emoji == avatarEmoji) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { avatarEmoji = emoji },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emoji, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("昵称") },
            singleLine = true,
            modifier = Modifier.width(350.dp),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            enabled = appState.roomSnapshot == null,
            label = { Text("服务器地址") },
            placeholder = { Text("房主手机的局域网地址") },
            singleLine = true,
            modifier = Modifier.width(350.dp),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    try {
                        val canChangeServer = appState.roomSnapshot == null
                        appState.settings.update(
                            displayName = displayName,
                            serverUrl = if (canChangeServer) serverUrl else null,
                            avatarEmoji = avatarEmoji,
                        )
                        if (canChangeServer) appState.serverUrl = com.synclisten.shared.data.normalizeServerUrl(serverUrl)
                        saved = true
                    } catch (_: Exception) { saved = false }
                }
            },
            modifier = Modifier.width(350.dp).height(48.dp),
        ) {
            Text(if (saved) "✅ 已保存" else "保存设置", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.width(350.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("用户 ID", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(settings.userId, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("此 ID 用于在房间中标识你的身份。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("快速切换服务器", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { serverUrl = "http://localhost:3000" },
                label = { Text("💻 本机") },
            )
            Text("手机托管：填写房主邀请中的 IP，不能填写 localhost。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
