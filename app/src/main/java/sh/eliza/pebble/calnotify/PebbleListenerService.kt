package sh.eliza.pebble.calnotify

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.util.UUID

private const val MSG_POLL = 0u

class PebbleListenerService : BasePebbleListenerService() {
    private lateinit var pebbleManager: PebbleManager

    override fun onCreate() {
        super.onCreate()
        pebbleManager = PebbleManager(applicationContext)
    }

    override fun onDestroy() {
        pebbleManager.close()
        super.onDestroy()
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult =
        try {
            require(watchappUUID == PebbleManager.APP_UUID) {
                "Expected watchapp UUID ${PebbleManager.APP_UUID}, but got: $watchappUUID"
            }

            val item = data[MSG_POLL]
            require(item is PebbleDictionaryItem.UInt32) {
                "Expected UInt32 command at key $MSG_POLL, but got: $item"
            }

            when (item.value) {
                MSG_POLL -> onPoll(watch)
                else -> throw IllegalArgumentException("Unknown command: ${item.value}")
            }

            ReceiveResult.Ack
        } catch (e: Throwable) {
            Log.e("PebbleListenerService", "Failed to handle message", e)
            ReceiveResult.Nack
        }

    private suspend fun onPoll(watch: WatchIdentifier) {
        val settingsRepository = SettingsRepository(dataStore, coroutineScope)
        val settings = settingsRepository.appSettingsFlow.filterNotNull().first()
        val alerts = Alert.getUpcomingAlerts(this, settings)
        pebbleManager.postAlerts(alerts, watch)
    }
}
