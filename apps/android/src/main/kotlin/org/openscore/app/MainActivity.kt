package org.openscore.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.openscore.app.alerts.AlertScheduler
import org.openscore.app.alerts.GameLink
import org.openscore.app.ui.MainScreen
import org.openscore.app.ui.theme.OpenScoreTheme

class MainActivity : ComponentActivity() {

    /** The game a notification tap asked for; cleared once the sheet has opened. */
    private val link = MutableStateFlow<GameLink?>(null)

    @OptIn(FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeLink(intent)
        setContent {
            val settings = OpenScoreApp.from(this).settings
            val darkMode by settings.darkMode.collectAsStateWithLifecycle()
            val dark = darkMode ?: isSystemInDarkTheme()
            // The system bars follow the app's own dark mode, not the phone's: the one-off
            // call above only knows the phone's, so a toggled theme left the status-bar clock
            // dark on dark until the activity was recreated.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark },
                )
                onDispose {}
            }
            val pendingLink by link.collectAsStateWithLifecycle()
            OpenScoreTheme(darkTheme = dark) {
                MainScreen(darkMode = darkMode, onDarkModeChange = settings::setDarkMode, link = pendingLink, onLinkHandled = { link.value = null })
            }
        }

        val app = OpenScoreApp.from(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Coming back to the app is the one wake-up that costs nothing: the schedule is
                // checked for staleness and anything the system has been sitting on is delivered.
                launch(Dispatchers.IO) {
                    AlertScheduler.ensureScheduled(app)
                    AlertScheduler.deliverOverdue(app)
                }
                // A changed bell or kind rebuilds the queue once the toggling has settled.
                launch(Dispatchers.IO) {
                    app.alerts.settings.drop(1).debounce(2_000).collect { AlertScheduler.refresh(app) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLink(intent)
    }

    private fun takeLink(intent: Intent?) {
        GameLink.from(intent)?.let { found ->
            link.value = found
            intent?.let(GameLink::clear)
        }
    }

    private companion object {
        /** `enableEdgeToEdge`'s own defaults; only three-button navigation on Android 8–9 still draws them. */
        val LIGHT_NAV_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
