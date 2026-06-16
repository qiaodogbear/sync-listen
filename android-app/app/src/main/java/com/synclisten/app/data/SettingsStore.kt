package com.synclisten.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.synclisten.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val userId: String = "",
    val displayName: String = "",
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val recentNickname: String = "",
    val avatarEmoji: String = "",
)

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun update(userId: String? = null, displayName: String? = null, serverUrl: String? = null, recentNickname: String? = null, avatarEmoji: String? = null)
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class PreferenceSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsStore {
    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            userId = preferences[USER_ID].orEmpty(),
            displayName = preferences[DISPLAY_NAME].orEmpty(),
            serverUrl = preferences[SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL,
            recentNickname = preferences[RECENT_NICKNAME].orEmpty(),
            avatarEmoji = preferences[AVATAR_EMOJI].orEmpty(),
        )
    }

    override suspend fun update(userId: String?, displayName: String?, serverUrl: String?, recentNickname: String?, avatarEmoji: String?) {
        context.settingsDataStore.edit { preferences ->
            userId?.let { preferences[USER_ID] = it }
            displayName?.let { preferences[DISPLAY_NAME] = it }
            serverUrl?.let { preferences[SERVER_URL] = normalizeServerUrl(it) }
            recentNickname?.let { preferences[RECENT_NICKNAME] = it }
            avatarEmoji?.let { preferences[AVATAR_EMOJI] = it }
        }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val SERVER_URL = stringPreferencesKey("server_url")
        val RECENT_NICKNAME = stringPreferencesKey("recent_nickname")
        val AVATAR_EMOJI = stringPreferencesKey("avatar_emoji")
    }
}

val AVATAR_EMOJIS = listOf("🎵","🎸","🎹","🎺","🎻","🥁","🎧","🎤","🎼","🎶","💿","🦊","🐱","🐶","🐼","🐨","🐰","🦄","🌈","🔥","⭐","🌸","🍕","🎮","🚀")

class IdentityManager(
    private val settingsStore: SettingsStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
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
