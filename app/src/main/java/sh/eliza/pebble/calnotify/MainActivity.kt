package sh.eliza.pebble.calnotify

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import sh.eliza.pebble.calnotify.ui.theme.PebbleKitSampleTheme

class MainActivity : ComponentActivity() {
    private val pebbleManager = PebbleManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Use SettingsRepository to fetch actual real-time settings
        val settingsRepository = SettingsRepository(dataStore)
        val settings = runBlocking { settingsRepository.appSettingsFlow.first() }

        Log.d("calnotify", "--- Upcoming Alerts ---")
        Alert.getUpcomingAlerts(this, settings).forEach { alert ->
            Log.d("calnotify", alert.toString())
        }

        enableEdgeToEdge()
        setContent {
            PebbleKitSampleTheme {
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
