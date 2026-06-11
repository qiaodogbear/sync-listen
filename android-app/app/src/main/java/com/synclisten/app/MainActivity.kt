package com.synclisten.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.synclisten.app.ui.SyncListenApp
import com.synclisten.app.ui.theme.SyncListenTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyncListenTheme {
                SyncListenApp()
            }
        }
    }
}

