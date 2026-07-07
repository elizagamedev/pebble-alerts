package sh.eliza.pebble.calnotify

import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val MSG_POLL = 0u

class PebbleListenerService : BasePebbleListenerService() {
    companion object {
        val inhibitUpdates = AtomicBoolean(false)

        inline fun <T> withInhibitUpdates(body: () -> T): T {
            inhibitUpdates.set(true)
            try {
                return body()
            } finally {
                inhibitUpdates.set(false)
            }
        }
    }

    private lateinit var pebbleManager: PebbleManager

    override fun onCreate() {
        super.onCreate()
        pebbleManager = PebbleManager(applicationContext)
    }

    override fun onDestroy() {
        pebbleManager.close()
        super.onDestroy()
    }

    override fun onAppOpened(
        watchappUUID: UUID,
        watch: WatchIdentifier,
    ) {
        if (watchappUUID != PebbleManager.APP_UUID) {
            return
        }
        if (inhibitUpdates.get()) {
            return
        }
        coroutineScope.launch {
            val settingsRepository = SettingsRepository(dataStore, coroutineScope)
            val settings = settingsRepository.appSettingsFlow.filterNotNull().first()
            val alerts = Alert.getUpcomingAlerts(applicationContext, settings)
            pebbleManager.send(settings.generalSettings, alerts, watch)
            settingsRepository.updateGeneralSettings {
                it.copy(
                    lastSynced = System.currentTimeMillis(),
                )
            }
        }
    }
}
