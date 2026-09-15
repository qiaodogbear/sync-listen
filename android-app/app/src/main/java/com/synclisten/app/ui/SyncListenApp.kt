package com.synclisten.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.synclisten.app.ui.screen.CreateRoomScreen
import com.synclisten.app.ui.screen.HomeScreen
import com.synclisten.app.ui.screen.InviteScreen
import com.synclisten.app.ui.screen.JoinRoomScreen
import com.synclisten.app.ui.screen.RoomScreen
import com.synclisten.app.ui.screen.SettingsScreen
import com.synclisten.app.ui.screen.UploadScreen
import com.synclisten.app.ui.theme.SyncListenTheme

private const val HOME = "home"
private const val CREATE = "create"
private const val JOIN = "join"
private const val ROOM = "room"
private const val UPLOAD = "upload"
private const val INVITE = "invite"
private const val SETTINGS = "settings"

private val slideSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = 400f,
)
private val fadeSpring = spring<Float>(
    dampingRatio = 0.6f,
    stiffness = 300f,
)

@Composable
fun SyncListenApp() {
    SyncListenTheme {
        val navController = rememberNavController()
        val entryViewModel: HomeViewModel = hiltViewModel()
        val pendingLink by entryViewModel.pendingJoinLink.collectAsState()
        val homeState by entryViewModel.state.collectAsState()
        val settings by entryViewModel.settings.collectAsState(initial = com.synclisten.app.data.AppSettings())
        var nickname by androidx.compose.runtime.remember(pendingLink) {
            androidx.compose.runtime.mutableStateOf(settings.recentNickname)
        }
        pendingLink?.let { link ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = entryViewModel::dismissJoinLink,
                title = { androidx.compose.material3.Text("加入邀请房间") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text("服务器：${link.serverUrl}")
                        if (homeState is HomeState.InRoom) {
                            androidx.compose.material3.Text("请先离开当前房间，再打开邀请链接。")
                        } else {
                            androidx.compose.material3.OutlinedTextField(
                                value = nickname, onValueChange = { nickname = it },
                                label = { androidx.compose.material3.Text("你的昵称") },
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        enabled = nickname.isNotBlank() && homeState !is HomeState.InRoom && homeState !is HomeState.Loading,
                        onClick = { entryViewModel.confirmJoinLink(nickname) },
                    ) { androidx.compose.material3.Text("确认加入") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = entryViewModel::dismissJoinLink) { androidx.compose.material3.Text("取消") } },
            )
        }

        LaunchedEffect(homeState) {
            if (homeState is HomeState.Idle && navController.currentDestination?.route == ROOM) {
                navController.popBackStack(HOME, inclusive = false)
            }
            if (homeState is HomeState.InRoom && navController.currentDestination?.route != ROOM) {
                navController.navigate(ROOM) { launchSingleTop = true; popUpTo(HOME) { inclusive = false } }
            }
        }
        Column(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = HOME, modifier = Modifier.weight(1f)) {
            composable(
                route = HOME,
                enterTransition = { fadeIn(animationSpec = fadeSpring) },
                exitTransition = { fadeOut(animationSpec = fadeSpring) },
            ) {
                HomeScreen(
                    onNavigateCreate = { navController.navigate(CREATE) },
                    onNavigateJoin = { navController.navigate(JOIN) },
                    onOpenSettings = { navController.navigate(SETTINGS) },
                    onEnteredRoom = {
                        navController.navigate(ROOM) {
                            launchSingleTop = true
                            popUpTo(HOME) { inclusive = false }
                        }
                    },
                )
            }

            composable(
                route = CREATE,
                enterTransition = {
                    slideInHorizontally(animationSpec = slideSpring) { it / 4 } +
                        fadeIn(animationSpec = fadeSpring)
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = slideSpring) { -it / 4 } +
                        fadeOut(animationSpec = fadeSpring)
                },
            ) {
                CreateRoomScreen(
                    onBack = { navController.popBackStack() },
                    onRoomCreated = {
                        navController.navigate(ROOM) {
                            launchSingleTop = true
                            popUpTo(HOME) { inclusive = false }
                        }
                    },
                )
            }

            composable(
                route = JOIN,
                enterTransition = {
                    slideInHorizontally(animationSpec = slideSpring) { it / 4 } +
                        fadeIn(animationSpec = fadeSpring)
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = slideSpring) { -it / 4 } +
                        fadeOut(animationSpec = fadeSpring)
                },
            ) {
                JoinRoomScreen(
                    onBack = { navController.popBackStack() },
                    onJoinedRoom = {
                        navController.navigate(ROOM) {
                            launchSingleTop = true
                            popUpTo(HOME) { inclusive = false }
                        }
                    },
                )
            }

            composable(
                route = ROOM,
                enterTransition = {
                    slideInHorizontally(animationSpec = slideSpring) { it / 4 } +
                        fadeIn(animationSpec = fadeSpring)
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = slideSpring) { -it / 4 } +
                        fadeOut(animationSpec = fadeSpring)
                },
            ) {
                RoomScreen(
                    onLeave = { navController.popBackStack(HOME, inclusive = false) },
                    onOpenUpload = { navController.navigate(UPLOAD) },
                    onOpenInvite = { navController.navigate(INVITE) },
                    onOpenSettings = { navController.navigate(SETTINGS) },
                )
            }

            composable(
                route = UPLOAD,
                enterTransition = {
                    slideInHorizontally(animationSpec = slideSpring) { it / 4 } +
                        fadeIn(animationSpec = fadeSpring)
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = slideSpring) { -it / 4 } +
                        fadeOut(animationSpec = fadeSpring)
                },
            ) {
                UploadScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = INVITE,
                enterTransition = {
                    slideInHorizontally(animationSpec = slideSpring) { it / 4 } +
                        fadeIn(animationSpec = fadeSpring)
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = slideSpring) { -it / 4 } +
                        fadeOut(animationSpec = fadeSpring)
                },
            ) {
                InviteScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = SETTINGS,
                enterTransition = {
                    slideInHorizontally(animationSpec = slideSpring) { it / 4 } +
                        fadeIn(animationSpec = fadeSpring)
                },
                exitTransition = {
                    slideOutHorizontally(animationSpec = slideSpring) { -it / 4 } +
                        fadeOut(animationSpec = fadeSpring)
                },
            ) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
        com.synclisten.app.ui.component.ConvenienceBar(onOpenRoom = {
            if (!navController.popBackStack(ROOM, inclusive = false)) {
                navController.navigate(ROOM) { launchSingleTop = true; popUpTo(HOME) { inclusive = false } }
            }
        })
        }
    }
}
