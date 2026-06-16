package com.synclisten.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.synclisten.shared.data.*
import com.synclisten.shared.data.RepositoryResult
import com.synclisten.shared.host.server.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(800.dp, 900.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "Sync Listen Desktop",
        state = windowState,
    ) {
        MaterialTheme {
            DesktopApp()
        }
    }
}

@Composable
fun DesktopApp() {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎧 Sync Listen Desktop", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("Windows 桌面版（共享模块构建成功）", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text("API 协议：REST + WebSocket", style = MaterialTheme.typography.bodySmall)
        Text("共享代码：domain / data / playback / host / invite / util", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = { tab = 0 }) { Text("启动 Host") }
            Button(onClick = { tab = 1 }) { Text("连接服务器") }
        }
        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> HostPanel()
            1 -> ClientPanel()
        }
    }
}

@Composable
fun HostPanel() {
    var status by remember { mutableStateOf("未启动") }
    var roomCode by remember { mutableStateOf("---") }
    val scope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = {
            scope.launch {
                try {
                    val store = HostRoomStore()
                    val hub = HostRoomHub()
                    val storage = HostStorage(File(System.getProperty("java.io.tmpdir"), "sync-listen-host").also { it.mkdirs() })
                    val server = embeddedServer(CIO, host = "0.0.0.0", port = 38571) {
                        hostServerModule(store = store, hub = hub, storage = storage)
                    }
                    server.start(wait = false)
                    status = "运行中 → http://localhost:38571"
                    val room = store.createRoom(CreateRoomRequest("DesktopRoom", "desktop-host", "Desktop Host"))
                    roomCode = room.room.roomCode
                    // Keep reference for later shutdown
                    Runtime.getRuntime().addShutdownHook(Thread { server.stop(500, 2000) })
                } catch (e: Exception) {
                    status = "启动失败: ${e.message}"
                }
            }
        }) { Text("创建托管房间") }
        Spacer(Modifier.height(8.dp))
        Text("状态: $status", style = MaterialTheme.typography.bodyMedium)
        Text("房间码: $roomCode", style = MaterialTheme.typography.titleLarge)
        Text("其他设备连接地址: http://你的电脑IP:38571", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ClientPanel() {
    var serverUrl by remember { mutableStateOf("http://localhost:38571") }
    var roomCode by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("DesktopUser") }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("服务器地址") })
        OutlinedTextField(roomCode, { roomCode = it.uppercase() }, label = { Text("房间码") })
        OutlinedTextField(name, { name = it }, label = { Text("昵称") })
        Button(onClick = {
            scope.launch {
                try {
                    val settings = MemorySettingsStore(AppSettings(serverUrl = serverUrl, displayName = name))
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val factory = RetrofitApiFactory(client, json)
                    val remote = RetrofitRoomRemoteDataSource(settings, factory)
                    val repo = RoomRepository(remote, json)
                    val identity = IdentityManager(settings)
                    identity.ensureIdentity()
                    val settingsVal = settings.settings.first { true }
                    val joinedResult = repo.joinRoom(roomCode.trim(), settingsVal.userId, name)
                    if (joinedResult is RepositoryResult.Success) {
                        val j = joinedResult.value
                        result = "✅ 加入成功！房间: ${j.room.name}，成员: ${j.member.displayName}"
                    } else {
                        result = "❌ 失败: ${(joinedResult as RepositoryResult.Failure).message}"
                    }
                } catch (e: Exception) {
                    result = "❌ 失败: ${e.message}"
                }
            }
        }) { Text("加入房间") }
        Spacer(Modifier.height(8.dp))
        Text(result, style = MaterialTheme.typography.bodyMedium)
    }
}
