package com.synclisten.shared.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
data class AppSettings(
    val userId: String = "",
    val displayName: String = "",
    val serverUrl: String = "http://localhost:3000",
    val recentNickname: String = "",
    val avatarEmoji: String = "",
    val deviceSecret: String = "",
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun update(
        userId: String? = null,
        displayName: String? = null,
        serverUrl: String? = null,
        recentNickname: String? = null,
        avatarEmoji: String? = null,
        deviceSecret: String? = null,
    )
}

/** 纯内存实现，供桌面端和测试使用 */
class MemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(
        userId: String?,
        displayName: String?,
        serverUrl: String?,
        recentNickname: String?,
        avatarEmoji: String?,
        deviceSecret: String?,
    ) {
        state.update { current -> current.copy(
            userId = userId ?: current.userId,
            displayName = displayName ?: current.displayName,
            serverUrl = serverUrl?.let { normalizeServerUrl(it) } ?: current.serverUrl,
            recentNickname = recentNickname ?: current.recentNickname,
            avatarEmoji = avatarEmoji ?: current.avatarEmoji,
            deviceSecret = deviceSecret ?: current.deviceSecret,
        ) }
    }
}

/** 基于 JSON 文件的持久化实现，供桌面端使用 */
class FileSettingsStore(private val file: File) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val state = MutableStateFlow(loadFromFile())
    private val writeMutex = Mutex()

    init {
        file.parentFile?.mkdirs()
    }

    override val settings: Flow<AppSettings> = state

    override suspend fun update(
        userId: String?,
        displayName: String?,
        serverUrl: String?,
        recentNickname: String?,
        avatarEmoji: String?,
        deviceSecret: String?,
    ) = writeMutex.withLock {
        val next = state.value.copy(
            userId = userId ?: state.value.userId,
            displayName = displayName ?: state.value.displayName,
            serverUrl = serverUrl?.let { normalizeServerUrl(it) } ?: state.value.serverUrl,
            recentNickname = recentNickname ?: state.value.recentNickname,
            avatarEmoji = avatarEmoji ?: state.value.avatarEmoji,
            deviceSecret = deviceSecret ?: state.value.deviceSecret,
        )
        withContext(Dispatchers.IO) { saveToFile(next) }
        state.value = next
    }

    private fun loadFromFile(): AppSettings {
        return if (file.isFile) {
            try {
                json.decodeFromString<AppSettings>(file.readText())
            } catch (_: Exception) {
                AppSettings()
            }
        } else {
            AppSettings()
        }
    }

    private fun saveToFile(settings: AppSettings) {
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), "settings-", ".tmp")
        try {
            Files.writeString(temp, json.encodeToString(settings))
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

val AVATAR_EMOJIS = listOf("🎵","🎸","🎹","🎺","🎻","🥁","🎧","🎤","🎼","🎶","💿","🦊","🐱","🐶","🐼","🐨","🐰","🦄","🌈","🔥","⭐","🌸","🍕","🎮","🚀")

class IdentityManager(
    private val settingsStore: SettingsStore,
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val emojiFactory: () -> String = { AVATAR_EMOJIS.random() },
) {
    suspend fun ensureIdentity(): AppSettings = identityLock.withLock {
        val current = settingsStore.settings.first()
        if (current.userId.isNotBlank() && current.deviceSecret.isNotBlank()) return@withLock current
        settingsStore.update(
            userId = current.userId.ifBlank { idFactory() },
            avatarEmoji = current.avatarEmoji.ifBlank { emojiFactory() },
            deviceSecret = current.deviceSecret.ifBlank { java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID() },
        )
        settingsStore.settings.first()
    }
}

private val identityLock = Mutex()

fun normalizeServerUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return trimmed
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "http://$trimmed"
    }
    return trimmed
}
