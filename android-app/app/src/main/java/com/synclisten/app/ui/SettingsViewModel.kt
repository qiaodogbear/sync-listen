package com.synclisten.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synclisten.app.data.AppSettings
import com.synclisten.app.data.IdentityManager
import com.synclisten.app.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    identityManager: IdentityManager,
) : ViewModel() {
    val settings = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    init {
        viewModelScope.launch { identityManager.ensureIdentity() }
    }

    fun save(displayName: String, serverUrl: String) {
        viewModelScope.launch {
            settingsStore.update(displayName = displayName.trim(), serverUrl = serverUrl)
        }
    }
}
