package com.synclisten.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.ui.HomeState
import com.synclisten.app.ui.HomeViewModel
import com.synclisten.app.ui.component.MemberAvatar
import com.synclisten.app.ui.component.RecoveryCard
import com.synclisten.app.ui.theme.*

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
    val settings by viewModel.settings.collectAsState(initial = com.synclisten.app.data.AppSettings())
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        if (state is HomeState.InRoom) onEnteredRoom()
        if (state is HomeState.Error) snackbar.showSnackbar((state as HomeState.Error).message)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg950)
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Aurora gradient header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                GradientAuroraStart.copy(alpha = 0.3f),
                                GradientAuroraEnd.copy(alpha = 0.1f),
                                Color.Transparent,
                            )
                        )
                    )
                    .padding(top = 32.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // User identity
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "你好，${settings.displayName.ifBlank { "用户" }}",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextSecondary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        MemberAvatar(
                            emoji = settings.avatarEmoji,
                            displayName = settings.displayName,
                            size = 40.dp,
                            onClick = onOpenSettings,
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // App title
                    Text(
                        "Sync Listen",
                        style = MaterialTheme.typography.displayLarge,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "和朋友一起 · 同步听歌",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Recovery card
            if (recoverableRoom != null) {
                RecoveryCard(
                    snapshot = recoverableRoom!!,
                    onRecover = { viewModel.recoverHostedRoom() },
                    onDismiss = { viewModel.dismissRecovery() },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            // Create Room Card
            Card(
                onClick = onNavigateCreate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Bg900),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Violet40, Violet20)
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🎵", style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "创建房间",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                        )
                        Text(
                            "手机托管，无需电脑",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }

            // Join Room Card
            Card(
                onClick = onNavigateJoin,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Bg900),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Orange40, Orange20)
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🔗", style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "加入房间",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                        )
                        Text(
                            "扫码 / 房间码 / 附近设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
