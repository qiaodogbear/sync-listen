package com.synclisten.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.synclisten.app.ui.component.RecoveryCard
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
            composable(HOME) {
                HomeScreen(
                    onNavigateCreate = { navController.navigate(CREATE) },
                    onNavigateJoin = { navController.navigate(JOIN) },
                    onOpenSettings = { navController.navigate(SETTINGS) },
                    onEnteredRoom = { navController.navigate(ROOM) {
                        popUpTo(HOME) { inclusive = false }
                    } },
                )
            }

            composable(CREATE) {
                CreateRoomScreen(
                    onBack = { navController.popBackStack() },
                    onRoomCreated = { navController.navigate(ROOM) {
                        popUpTo(HOME) { inclusive = false }
                    } },
                )
            }

            composable(JOIN) {
                val viewModel: HomeViewModel = hiltViewModel()
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state) {
                    if (state is HomeState.InRoom) {
                        navController.navigate(ROOM) {
                            popUpTo(HOME) { inclusive = false }
                        }
                    }
                }

                JoinRoomScreen(
                    onBack = { navController.popBackStack() },
                    onJoinedRoom = { navController.navigate(ROOM) {
                        popUpTo(HOME) { inclusive = false }
                    } },
                )
            }

            composable(ROOM) {
                RoomScreen(
                    onLeave = { navController.popBackStack(HOME, inclusive = false) },
                    onOpenUpload = { navController.navigate(UPLOAD) },
                    onOpenInvite = { navController.navigate(INVITE) },
                    onOpenSettings = { navController.navigate(SETTINGS) },
                )
            }

            composable(UPLOAD) {
                UploadScreen(onBack = { navController.popBackStack() })
            }

            composable(INVITE) {
                InviteScreen(onBack = { navController.popBackStack() })
            }

            composable(SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
