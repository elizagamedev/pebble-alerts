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
import kotlinx.coroutines.launch
import sh.eliza.pebble.calnotify.ui.theme.AppTheme

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    private lateinit var pebbleManager: PebbleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        pebbleManager = PebbleManager(applicationContext)

        // Use SettingsRepository to fetch actual real-time settings
        val settingsRepository = SettingsRepository(dataStore, lifecycleScope)

        splashScreen.setKeepOnScreenCondition { settingsRepository.appSettingsFlow.value == null }

        enableEdgeToEdge()
        setContent {
            AppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        repository = settingsRepository,
                        onSyncRequest = {
                            lifecycleScope.launch {
                                onSyncRequest(settingsRepository)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        pebbleManager.close()
        super.onDestroy()
    }

    private suspend fun onSyncRequest(settingsRepository: SettingsRepository) {
        val settings = settingsRepository.appSettingsFlow.value ?: return
        PebbleListenerService.withInhibitUpdates {
            val alerts = Alert.getUpcomingAlerts(this@MainActivity, settings)
            if (pebbleManager.openAppOnWatch()) {
                pebbleManager.send(settings.generalSettings, alerts.asSequence())
                settingsRepository.updateGeneralSettings {
                    it.copy(lastSynced = System.currentTimeMillis())
                }
            }
        }
    }
}
