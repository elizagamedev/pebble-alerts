package sh.eliza.pebble.calnotify

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.time.Instant
import java.util.UUID

private const val MSG_POST_SETTINGS = 0u
private const val MSG_POST_ALERTS = 1u

private const val MAX_ALERTS = 32

class PebbleManager(
    private val context: Context,
) : AutoCloseable {
    private val sender: PebbleSender = DefaultPebbleSender(context)

    override fun close() {
        // No-op: The underlying service connection is bound to the applicationContext
        // and persists for the lifetime of the application process. We let the OS reclaim
        // the connection on process termination to avoid double-unbinding crashes
        // caused by the SDK's internal unbind lifecycle (e.g., onBindingDied).
    }

    suspend fun openAppOnWatch(watch: WatchIdentifier? = null): Boolean {
        val result = sender.startAppOnTheWatch(APP_UUID, watch?.let { listOf(it) })
        return result?.values?.any { it == TransmissionResult.Success } == true
    }

    suspend fun postSettings(
        settings: GeneralSettings,
        watch: WatchIdentifier? = null,
    ) {
        val dict =
            mapOf<UInt, PebbleDictionaryItem>(
                0u to PebbleDictionaryItem.UInt32(MSG_POST_SETTINGS),
                1u to
                    PebbleDictionaryItem.Int32(
                        settings.syncInterval?.inWholeSeconds?.toInt() ?: -1,
                    ),
            )
        sender.sendDataToPebble(APP_UUID, dict, watch?.let { listOf(it) })
    }

    suspend fun postAlerts(
        alerts: Sequence<Alert>,
        watch: WatchIdentifier? = null,
    ) {
        val now = Instant.now()
        val filteredAlerts = alerts.filter { it.startTime >= now }.take(MAX_ALERTS).toList()

        val dict =
            mutableMapOf<UInt, PebbleDictionaryItem>(
                0u to PebbleDictionaryItem.UInt32(MSG_POST_ALERTS),
                1u to PebbleDictionaryItem.UInt32(filteredAlerts.size.toUInt()),
            )
        val size = 9u
        val start = 2u
        filteredAlerts.forEachIndexed { i, alert ->
            val base = start + size * i.toUInt()
            dict[base + 0u] = PebbleDictionaryItem.UInt32(alert.id)
            dict[base + 1u] = PebbleDictionaryItem.Text(alert.calendarName)
            dict[base + 2u] = PebbleDictionaryItem.Text(alert.title)
            dict[base + 3u] = PebbleDictionaryItem.Text(alert.details)
            dict[base + 4u] = PebbleDictionaryItem.Text(alert.location)
            dict[base + 5u] = PebbleDictionaryItem.UInt32(alert.startTime.epochSecond.toUInt())
            dict[base + 6u] = PebbleDictionaryItem.UInt32(alert.endTime.epochSecond.toUInt())
            dict[base + 7u] = PebbleDictionaryItem.UInt32(alert.alertTime.epochSecond.toUInt())
            dict[base + 8u] = PebbleDictionaryItem.UInt8(alert.color.toUByte())
        }
        sender.sendDataToPebble(APP_UUID, dict, watch?.let { listOf(it) })
    }

    companion object {
        val APP_UUID = UUID.fromString("075a861e-c60b-4bb6-b3f2-b592925e86b1")
    }
}
