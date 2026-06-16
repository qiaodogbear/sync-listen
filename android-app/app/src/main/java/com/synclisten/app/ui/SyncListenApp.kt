package com.synclisten.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun SyncListenApp() {
    SyncListenTheme {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = HOME) {
            composable(
                route = HOME,
                exitTransition = { fadeOut() },
                enterTransition = { fadeIn() },
            ) {
                HomeScreen(
                    onNavigateCreate = { navController.navigate(CREATE) },
                    onNavigateJoin = { navController.navigate(JOIN) },
                    onOpenSettings = { navController.navigate(SETTINGS) },
                    onEnteredRoom = { navController.navigate(ROOM) {
                        popUpTo(HOME) { inclusive = false }
                    } },
                )
            }

            composable(
                route = CREATE,
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
            ) {
                CreateRoomScreen(
                    onBack = { navController.popBackStack() },
                    onRoomCreated = { navController.navigate(ROOM) {
                        popUpTo(HOME) { inclusive = false }
                    } },
                )
            }

            composable(
                route = JOIN,
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
            ) {
                val viewModel: HomeViewModel = hiltViewModel()
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state) {
                    if (state is HomeState.InRoom) {
                        navController.navigate(ROOM) { popUpTo(HOME) { inclusive = false } }
                    }
                }
                JoinRoomScreen(
                    onBack = { navController.popBackStack() },
                    onJoinedRoom = { navController.navigate(ROOM) { popUpTo(HOME) { inclusive = false } } },
                )
            }

            composable(
                route = ROOM,
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
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
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
            ) {
                UploadScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = INVITE,
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
            ) {
                InviteScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = SETTINGS,
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
            ) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
