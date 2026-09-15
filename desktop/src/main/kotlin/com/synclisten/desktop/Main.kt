package com.synclisten.desktop

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.synclisten.desktop.ui.HomeScreen
import com.synclisten.desktop.ui.CreateScreen
import com.synclisten.desktop.ui.JoinScreen
import com.synclisten.desktop.ui.RoomScreen
import com.synclisten.desktop.ui.SettingsScreen
import com.synclisten.desktop.ui.theme.SyncListenDesktopTheme

fun main() {
    // Write startup log to confirm app launched
    try {
        java.io.File(System.getProperty("user.home") + "/synclisten_startup.log").writeText(
            "SyncListen started at ${java.time.Instant.now()}\n" +
            "Java: ${System.getProperty("java.version")} ${System.getProperty("os.name")}\n"
        )
    } catch (_: Exception) {}

    application {
        val windowState = rememberWindowState(size = DpSize(900.dp, 700.dp))
        val appState = remember { DesktopAppState() }

        Window(
            onCloseRequest = {
                appState.close()
                exitApplication()
            },
            title = "Sync Listen",
            state = windowState,
        ) {
            SyncListenDesktopTheme {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = appState.currentScreen,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val direction = when (targetState) {
                                Screen.Home -> -1
                                Screen.Settings -> 1
                                else -> if (targetState.ordinal > initialState.ordinal) 1 else -1
                            }
                            slideInHorizontally { width -> direction * (width / 4) } +
                                fadeIn() togetherWith
                                slideOutHorizontally { width -> -direction * (width / 4) } +
                                fadeOut()
                        },
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> HomeScreen(appState)
                            Screen.Create -> CreateScreen(appState)
                            Screen.Join -> JoinScreen(appState)
                            Screen.Room -> RoomScreen(appState)
                            Screen.Settings -> SettingsScreen(appState)
                        }
                    }
                }
            }
        }
    }
}
