package sh.eliza.pebble.calnotify

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

private const val MSG_VERSION = 0u
private const val MAX_ALERTS = 6

class PebbleManager(
    private val context: Context,
) : AutoCloseable {
    val sender: PebbleSender = DefaultPebbleSender(context)

    override fun close() {
        // No-op: The underlying service connection is bound to the applicationContext
        // and persists for the lifetime of the application process. We let the OS reclaim
        // the connection on process termination to avoid double-unbinding crashes
        // caused by the SDK's internal unbind lifecycle (e.g., onBindingDied).
    }

    suspend inline fun <T> withOpenAppOnWatch(
        watch: WatchIdentifier? = null,
        crossinline body: suspend () -> T,
    ): T? =
        PebbleListenerService.withTransaction(watch) { onAppOpened, onAppClosed ->
            val result = sender.startAppOnTheWatch(APP_UUID, watch?.let { listOf(it) })
            val willOpen = if (watch != null) {
                result?.get(watch) == TransmissionResult.Success
            } else {
                result?.values?.any { it == TransmissionResult.Success } ?: false
            }
            if (!willOpen) {
                return@withTransaction null
            }

            withTimeoutOrNull(2000) {
                onAppOpened.await()
                val result = body()
                onAppClosed.await()
                result
            }
        }

    suspend fun send(
        settings: GeneralSettings,
        alerts: Sequence<Alert>,
        watch: WatchIdentifier? = null,
    ) {
        val now = Instant.now()
        val filteredAlerts = alerts.filter { now <= it.endTime }.take(MAX_ALERTS).toList()

        // Convert all strings into a heap.
        val (stringHeap, strings) =
            makeStringHeap(
                mutableSetOf<String>().apply {
                    filteredAlerts.forEach {
                        add(it.calendarName)
                        add(it.title)
                        add(it.details)
                        add(it.location)
                    }
                },
            )

        val payloadLength = (4 + filteredAlerts.size * 10) * 4 + stringHeap.size

        val payload =
            ByteBuffer.allocate(payloadLength).apply {
                order(ByteOrder.LITTLE_ENDIAN)

                putInt(settings.snoozeDuration.inWholeSeconds.toInt())
                putInt(filteredAlerts.size)
                putInt(stringHeap.size)
                putInt(settings.vibePattern.value)

                filteredAlerts.forEach { alert ->
                    putInt(alert.id.toInt())
                    putInt(alert.alertTime.epochSecond.toInt())
                    putInt(alert.startTime.epochSecond.toInt())
                    putInt(alert.endTime.epochSecond.toInt())
                    putInt(alert.color.toInt())
                    putInt(strings.getValue(alert.calendarName))
                    putInt(strings.getValue(alert.title))
                    putInt(strings.getValue(alert.details))
                    putInt(strings.getValue(alert.location))
                    putInt(if (alert.alertTime > now) alert.alertTime.epochSecond.toInt() else -1) // alarm_time
                }

                put(stringHeap)

                check(!hasRemaining())
            }

        val dict =
            mapOf<UInt, PebbleDictionaryItem>(
                0u to PebbleDictionaryItem.UInt32(MSG_VERSION),
                1u to PebbleDictionaryItem.Bytes(payload.array()),
            )

        sender.sendDataToPebble(APP_UUID, dict, watch?.let { listOf(it) })
    }

    fun makeStringHeap(strings: Set<String>): Pair<ByteArray, Map<String, Int>> {
        val outputStream = ByteArrayOutputStream()
        val offsetMap = mutableMapOf<String, Int>()

        for (str in strings) {
            offsetMap[str] = outputStream.size()
            outputStream.write(str.toByteArray(StandardCharsets.UTF_8))
            outputStream.write(0)
        }

        return Pair(outputStream.toByteArray(), offsetMap)
    }

    companion object {
        val APP_UUID = UUID.fromString("075a861e-c60b-4bb6-b3f2-b592925e86b1")
    }
}
