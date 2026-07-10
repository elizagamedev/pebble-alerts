package sh.eliza.pebble.calnotify

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TimelineLayout
import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration.Companion.hours

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
            val willOpen =
                if (watch != null) {
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
        settings: AppSettings,
        alerts: Sequence<Alert>,
        watch: WatchIdentifier? = null,
    ) {
        val now = Instant.now()
        val filteredAlerts = alerts.filter { now <= it.endTime }.take(MAX_ALERTS).toList()

        syncContactTimelinePins(context, settings.contactSettings, now, sender)

        // Convert all strings into a heap.
        val (stringHeap, strings) = makeStringHeap(filteredAlerts)

        val payloadLength = (4 + filteredAlerts.size * 10) * 4 + stringHeap.size

        val payload =
            ByteBuffer.allocate(payloadLength).apply {
                order(ByteOrder.LITTLE_ENDIAN)

                putInt(
                    settings.generalSettings.snoozeDuration.inWholeSeconds
                        .toInt(),
                )
                putInt(filteredAlerts.size)
                putInt(stringHeap.size)
                putInt(settings.generalSettings.vibePattern.value)

                filteredAlerts.forEach { alert ->
                    putInt(alert.id.toInt())
                    putInt(alert.alertTime.epochSecond.toInt())
                    putInt(alert.startTime.epochSecond.toInt())
                    putInt(alert.endTime.epochSecond.toInt())
                    val flags =
                        (alert.color.toInt() and 0x3F) or
                            (if (alert.allDay) 0x80000000.toInt() else 0)
                    putInt(flags)
                    putInt(strings[alert.calendarName] ?: 0)
                    putInt(strings[alert.title] ?: 0)
                    putInt(strings[alert.details] ?: 0)
                    putInt(strings[alert.location] ?: 0)
                    putInt(if (alert.alertTime > now) alert.alertTime.epochSecond.toInt() else -1)
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

    fun makeStringHeap(alerts: List<Alert>): Pair<ByteArray, Map<String, Int>> {
        val outputStream = ByteArrayOutputStream()
        val offsetMap = mutableMapOf<String, Int>()

        outputStream.write("...".toByteArray(StandardCharsets.UTF_8))
        outputStream.write(0)
        offsetMap["..."] = 0
        offsetMap[""] = 3

        fun addString(str: String) {
            if (offsetMap.containsKey(str)) {
                return
            }
            val bytes = str.toByteArray(StandardCharsets.UTF_8)
            if (outputStream.size() + bytes.size + 1 <= 4096) {
                offsetMap[str] = outputStream.size()
                outputStream.write(bytes)
                outputStream.write(0)
            }
        }

        alerts.forEach { addString(it.title) }
        alerts.forEach { addString(it.calendarName) }
        alerts.forEach { addString(it.location) }
        alerts.forEach { addString(it.details) }

        return Pair(outputStream.toByteArray(), offsetMap)
    }

    companion object {
        val APP_UUID = UUID.fromString("075a861e-c60b-4bb6-b3f2-b592925e86b1")
    }
}

private suspend fun syncContactTimelinePins(
    context: Context,
    contactSettings: Map<ContactEventType, ContactSettings>,
    now: Instant,
    sender: PebbleSender,
) {
    val maxPinTime = now.plus(2, ChronoUnit.DAYS)
    val minPinTime = now.minus(1, ChronoUnit.DAYS)

    val pinsToSync =
        Alert
            .visitUpcomingContactEvents(context, contactSettings) {
                config,
                id,
                _,
                title,
                dayOfDetails,
                _,
                startTime,
                subtitle,
                ->
                if (startTime <= maxPinTime && startTime >= minPinTime) {
                    val pinId = "contact-$id"
                    if (config.timelinePins) {
                        val pin =
                            TimelinePin(
                                id = pinId,
                                startTime =
                                    kotlin.time.Instant.fromEpochMilliseconds(
                                        startTime.toEpochMilli(),
                                    ),
                                duration = 24.hours,
                                layout =
                                    TimelineLayout(
                                        type = TimelineLayoutType.GENERIC_PIN,
                                        title = title,
                                        subtitle = subtitle,
                                        body = dayOfDetails.takeIf { it.isNotBlank() },
                                        tinyIcon = "system://images/BIRTHDAY_EVENT",
                                        largeIcon = "system://images/BIRTHDAY_EVENT",
                                    ),
                            )
                        sequenceOf(pinId to pin)
                    } else {
                        sequenceOf(pinId to null)
                    }
                } else {
                    emptySequence<Pair<String, TimelinePin?>>()
                }
            }.toList()

    for ((pinId, pin) in pinsToSync) {
        if (pin != null) {
            sender.insertTimelinePin(PebbleManager.APP_UUID, pin)
        } else {
            sender.deleteTimelinePin(PebbleManager.APP_UUID, pinId)
        }
    }
}
