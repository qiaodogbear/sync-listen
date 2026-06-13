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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var joinLinkInbox: JoinLinkInbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let(joinLinkInbox::accept)
        setContent {
            SyncListenTheme {
                SyncListenApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        joinLinkInbox.accept(intent.dataString)
    }
}
