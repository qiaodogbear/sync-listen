package com.synclisten.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.synclisten.app.ui.SyncListenApp
import com.synclisten.app.ui.theme.SyncListenTheme
import dagger.hilt.android.AndroidEntryPoint
import com.synclisten.app.invite.JoinLinkInbox
import javax.inject.Inject
import com.synclisten.app.nearby.NfcJoinManager

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var joinLinkInbox: JoinLinkInbox
    @Inject lateinit var nfcJoinManager: NfcJoinManager
    @Inject lateinit var nearbyManager: com.synclisten.app.nearby.LanNearbyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcJoinManager.handleIntent(intent)
        setContent {
            SyncListenTheme {
                SyncListenApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        nearbyManager.setForeground(true)
    }

    override fun onStop() {
        nearbyManager.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nfcJoinManager.handleIntent(intent)
    }
}
