package com.whereareu.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.whereareu.app.core.AppGraph
import com.whereareu.app.ui.nav.AppRoot
import com.whereareu.app.ui.theme.MiroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        handleIntent(intent)
        // The app is light-only (Miro white/pastel canvas). Always request dark
        // status/navigation bar icons so they stay legible on white and pastel
        // surfaces, even when the system is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            MiroTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Routes notification taps into the Compose tree. */
    private fun handleIntent(intent: Intent?) {
        val data = intent ?: return
        val memberId = data.getStringExtra(EXTRA_MEMBER_ID)
        if (!memberId.isNullOrBlank()) {
            AppGraph.requestMemberFocus(memberId)
            data.removeExtra(EXTRA_MEMBER_ID)
        }
        if (data.getBooleanExtra(EXTRA_OPEN_ALERTS, false)) {
            AppGraph.requestAlertsOpen()
            data.removeExtra(EXTRA_OPEN_ALERTS)
        }
    }

    companion object {
        const val EXTRA_MEMBER_ID = "com.whereareu.app.extra.MEMBER_ID"
        const val EXTRA_OPEN_ALERTS = "com.whereareu.app.extra.OPEN_ALERTS"
    }
}
