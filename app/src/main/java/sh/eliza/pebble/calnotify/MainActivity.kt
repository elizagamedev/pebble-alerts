package sh.eliza.pebble.calnotify

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.eliza.pebble.calnotify.ui.theme.AppTheme

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    private val pebbleManager = PebbleManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Use SettingsRepository to fetch actual real-time settings
        val settingsRepository = SettingsRepository(dataStore, lifecycleScope)

        splashScreen.setKeepOnScreenCondition { settingsRepository.appSettingsFlow.value == null }

        // Reactively push alerts to the watch whenever alert-specific settings change
        lifecycleScope.launch {
            settingsRepository.appSettingsFlow
                .filterNotNull()
                .drop(1) // Skip the initial emission on app launch
                .distinctUntilChanged { old, new ->
                    old.calendarSettings == new.calendarSettings &&
                        old.contactSettings == new.contactSettings
                }.collectLatest { settings ->
                    val alerts = Alert.getUpcomingAlerts(this@MainActivity, settings)
                    pebbleManager.openAppOnWatch()
                    pebbleManager.postAlerts(alerts)
                }
        }

        // Reactively push sync interval setting to the watch whenever it changes
        lifecycleScope.launch {
            settingsRepository.appSettingsFlow
                .filterNotNull()
                .map { it.generalSettings }
                .drop(1) // Skip the initial emission on app launch
                .collectLatest { settings ->
                    pebbleManager.openAppOnWatch()
                    pebbleManager.postSettings(settings)
                }
        }

        enableEdgeToEdge()
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(repository = settingsRepository)
                }
            }
        }
    }

    override fun onDestroy() {
        pebbleManager.close()
        super.onDestroy()
    }
}
