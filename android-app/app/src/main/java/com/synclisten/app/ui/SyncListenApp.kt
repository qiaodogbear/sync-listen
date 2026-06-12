package com.synclisten.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }

    LaunchedEffect(settings) {
        displayName = settings.displayName
        serverUrl = settings.serverUrl
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "调试设置", style = MaterialTheme.typography.headlineMedium)
        Text(text = "用户 ID：${settings.userId}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("临时昵称") },
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("服务器地址") },
        )
        Button(onClick = { viewModel.save(displayName, serverUrl) }) {
            Text("保存")
        }
        Button(onClick = onBack) {
            Text("返回")
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
