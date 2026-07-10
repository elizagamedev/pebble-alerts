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
import java.time.Instant

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private const val LOREM_IPSUM =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor " +
        "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud " +
        "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n\n" +
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu " +
        "fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in " +
        "culpa qui officia deserunt mollit anim id est laborum.\n\n" +
        "Curabitur pretium tincidunt lacus. Nulla gravida orci a odio. Nullam varius, " +
        "turpis et commodo pharetra, est eros bibendum elit, nec luctus magna felis " +
        "sollicitudin mauris."

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
                        onSyncTestDataRequest = {
                            lifecycleScope.launch {
                                syncTestData(settingsRepository)
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
        val alerts = Alert.getUpcomingAlerts(this@MainActivity, settings)
        pebbleManager.withOpenAppOnWatch {
            pebbleManager.send(settings, alerts.asSequence())
            settingsRepository.updateGeneralSettings {
                it.copy(lastSynced = System.currentTimeMillis())
            }
        }
    }

    private suspend fun syncTestData(settingsRepository: SettingsRepository) {
        val settings = settingsRepository.appSettingsFlow.value ?: return
        val now = Instant.now()
        val alerts =
            listOf(
                Alert(
                    id = 1001u,
                    calendarName = "Work Calendar",
                    title = "Sync Meeting",
                    details = LOREM_IPSUM,
                    location = "Room 404",
                    startTime = now.plusSeconds(3600),
                    endTime = now.plusSeconds(7200),
                    alertTime = now.plusSeconds(5),
                    color = PebbleColor.fromRgb(0x00FF00),
                    allDay = false,
                ),
                Alert(
                    id = 1002u,
                    calendarName = "Personal Calendar",
                    title = "Call Mom",
                    details = "",
                    location = "",
                    startTime = now.plusSeconds(7200),
                    endTime = now.plusSeconds(10800),
                    alertTime = now.plusSeconds(5),
                    color = PebbleColor.fromRgb(0xFF0000),
                    allDay = false,
                ),
                Alert(
                    id = 1004u,
                    calendarName = "Work Calendar",
                    title = "1-on-1 with Boss",
                    details = "Performance review",
                    location = "Boss's Office",
                    startTime = now.plusSeconds(3600 * 4),
                    endTime = now.plusSeconds(3600 * 5),
                    alertTime = now.plusSeconds(30),
                    color = PebbleColor.fromRgb(0x00FF00),
                    allDay = false,
                ),
                Alert(
                    id = 1005u,
                    calendarName = "Personal Calendar",
                    title = "Dentist Appointment",
                    details = "Routine checkup",
                    location = "Dr. Smith's Clinic",
                    startTime = now.plusSeconds(3600 * 24),
                    endTime = now.plusSeconds(3600 * 25),
                    alertTime = now.plusSeconds(3600 * 24),
                    color = PebbleColor.fromRgb(0x0000FF),
                    allDay = false,
                ),
                Alert(
                    id = 1003u,
                    calendarName = "Birthdays",
                    title = "John's Birthday",
                    details = "He is 30 today!",
                    location = "",
                    startTime = now.plusSeconds(86400),
                    endTime = now.plusSeconds(86400 * 2),
                    alertTime = now.plusSeconds(5),
                    color = PebbleColor.fromRgb(0x0000FF),
                    allDay = true,
                ),
            )

        pebbleManager.withOpenAppOnWatch {
            pebbleManager.send(
                settings,
                alerts.asSequence(),
            )
        }
    }
}
