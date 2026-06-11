package com.synclisten.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private const val HOME_ROUTE = "home"
private const val SETTINGS_ROUTE = "settings"

@Composable
fun SyncListenApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HOME_ROUTE) {
        composable(HOME_ROUTE) {
            PlaceholderScreen(
                title = "Sync Listen",
                body = "多人同步听歌原型",
                actionLabel = "调试设置",
                onAction = { navController.navigate(SETTINGS_ROUTE) },
            )
        }
        composable(SETTINGS_ROUTE) {
            PlaceholderScreen(
                title = "调试设置",
                body = "默认服务器：http://10.0.2.2:3000",
                actionLabel = "返回",
                onAction = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = body, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

