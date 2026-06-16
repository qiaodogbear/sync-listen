package com.synclisten.shared.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

data class AppSettings(
    val userId: String = "",
    val displayName: String = "",
    val serverUrl: String = "http://10.0.2.2:3000",
    val recentNickname: String = "",
    val avatarEmoji: String = "",
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun update(
        userId: String? = null,
        displayName: String? = null,
        serverUrl: String? = null,
        recentNickname: String? = null,
        avatarEmoji: String? = null,
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
    ) {
        state.value = state.value.copy(
            userId = userId ?: state.value.userId,
            displayName = displayName ?: state.value.displayName,
            serverUrl = serverUrl?.let { normalizeServerUrl(it) } ?: state.value.serverUrl,
            recentNickname = recentNickname ?: state.value.recentNickname,
            avatarEmoji = avatarEmoji ?: state.value.avatarEmoji,
        )
    }
}

val AVATAR_EMOJIS = listOf("🎵","🎸","🎹","🎺","🎻","🥁","🎧","🎤","🎼","🎶","💿","🦊","🐱","🐶","🐼","🐨","🐰","🦄","🌈","🔥","⭐","🌸","🍕","🎮","🚀")

class IdentityManager(
    private val settingsStore: SettingsStore,
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val emojiFactory: () -> String = { AVATAR_EMOJIS.random() },
) {
    suspend fun ensureIdentity(): AppSettings {
        val current = settingsStore.settings.first()
        if (current.userId.isNotBlank()) return current
        settingsStore.update(userId = idFactory(), avatarEmoji = emojiFactory())
        return settingsStore.settings.first()
    }
}

fun normalizeServerUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return trimmed
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "http://$trimmed"
    }
    return trimmed
}
