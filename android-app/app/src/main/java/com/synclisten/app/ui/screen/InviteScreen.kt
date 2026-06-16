package com.synclisten.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.synclisten.app.invite.QrCodeCodec
import com.synclisten.app.ui.InviteViewModel

@Composable
fun InviteScreen(
    onBack: () -> Unit,
    viewModel: InviteViewModel = hiltViewModel(),
) {
    val link by viewModel.link.collectAsState()
    val bleState by viewModel.bleState.collectAsState()
    var bleDenied by remember { mutableStateOf(false) }
    val blePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        bleDenied = grants.values.any { !it }
        if (!bleDenied) viewModel.startBleInvite()
    }
    val bitmap = remember(link) { link?.let { QrCodeCodec.bitmap(it, 768).asImageBitmap() } }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("邀请成员", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))

            // 二维码 - 大尺寸居中
            bitmap?.let {
                Image(bitmap = it, contentDescription = "加入房间二维码",
                    modifier = Modifier.size(280.dp))
                Spacer(Modifier.height(16.dp))
                Text(link ?: "正在生成...", style = MaterialTheme.typography.bodyMedium)
            } ?: Text("正在生成邀请链接...", style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(24.dp))

            // BLE 广播
            Text("BLE 广播：${bleState.status.name}",
                style = MaterialTheme.typography.bodySmall)
            bleState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                val missing = viewModel.requiredBlePermissions()
                if (missing.isEmpty()) viewModel.startBleInvite()
                else blePermissions.launch(missing.toTypedArray())
            }) { Text("通过 BLE 广播房间码") }
            if (bleDenied) Text("蓝牙权限被拒绝，二维码邀请仍可使用",
                color = MaterialTheme.colorScheme.error)

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBack) { Text("← 返回") }
        }
    }
}
