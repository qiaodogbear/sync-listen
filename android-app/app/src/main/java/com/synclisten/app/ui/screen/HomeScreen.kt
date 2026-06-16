package com.synclisten.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.ui.HomeState
import com.synclisten.app.ui.HomeViewModel
import com.synclisten.app.ui.component.RecoveryCard

@Composable
fun HomeScreen(
    onNavigateCreate: () -> Unit,
    onNavigateJoin: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnteredRoom: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val recoverableRoom by viewModel.recoverableRoom.collectAsState()
    val hostServerState by viewModel.hostServerState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        if (state is HomeState.InRoom) onEnteredRoom()
        if (state is HomeState.Error) snackbar.showSnackbar((state as HomeState.Error).message)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("🎧 Sync Listen", style = MaterialTheme.typography.displayLarge)
            Text("和朋友一起听歌", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))

            // 创建房间卡片
            ElevatedCard(
                onClick = onNavigateCreate,
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🎵", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("创建房间", style = MaterialTheme.typography.titleLarge)
                    Text("手机托管，无需电脑",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 加入房间卡片
            ElevatedCard(
                onClick = onNavigateJoin,
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("🔗", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("加入房间", style = MaterialTheme.typography.titleLarge)
                    Text("扫码 / 附近房间 / 房间码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))

            // 恢复卡片
            recoverableRoom?.let { snapshot ->
                RecoveryCard(
                    snapshot = snapshot,
                    onRecover = { viewModel.recoverHostedRoom() },
                    onDismiss = { viewModel.dismissRecovery() },
                )
                Spacer(Modifier.height(16.dp))
            }

            // 加载指示器
            if (state is HomeState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            // 网络状态
            Text(
                "手机托管：$hostServerState",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "调试设置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
