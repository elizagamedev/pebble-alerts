package sh.eliza.pebble.calnotify

import android.content.Context
import androidx.compose.ui.graphics.Color
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import java.util.UUID

fun androidToPebbleColor(androidColor: Int): UByte {
    val r = (androidColor shr 16 and 0xFF) shr 6
    val g = (androidColor shr 8 and 0xFF) shr 6
    val b = (androidColor and 0xFF) shr 6
    return (0xC0 or (r shl 4) or (g shl 2) or b).toUByte()
}

private val PEBBLE_COLOR_MAP =
    mapOf<UByte, Color>(
        192u.toUByte() to Color(0xFF000000),
        193u.toUByte() to Color(0xFF001E41),
        194u.toUByte() to Color(0xFF004387),
        195u.toUByte() to Color(0xFF0068CA),
        196u.toUByte() to Color(0xFF2B4A2C),
        197u.toUByte() to Color(0xFF27514F),
        198u.toUByte() to Color(0xFF16638D),
        199u.toUByte() to Color(0xFF007DCE),
        200u.toUByte() to Color(0xFF5E9860),
        201u.toUByte() to Color(0xFF5C9B72),
        202u.toUByte() to Color(0xFF57A5A2),
        203u.toUByte() to Color(0xFF4CB4DB),
        204u.toUByte() to Color(0xFF8EE391),
        205u.toUByte() to Color(0xFF8EE69E),
        206u.toUByte() to Color(0xFF8AEBC0),
        207u.toUByte() to Color(0xFF84F5F1),
        208u.toUByte() to Color(0xFF4A161B),
        209u.toUByte() to Color(0xFF482748),
        210u.toUByte() to Color(0xFF40488A),
        211u.toUByte() to Color(0xFF2F6BCC),
        212u.toUByte() to Color(0xFF564E36),
        213u.toUByte() to Color(0xFF545454),
        214u.toUByte() to Color(0xFF4F6790),
        215u.toUByte() to Color(0xFF4180D0),
        216u.toUByte() to Color(0xFF759A64),
        217u.toUByte() to Color(0xFF759D76),
        218u.toUByte() to Color(0xFF71A6A4),
        219u.toUByte() to Color(0xFF69B5DD),
        220u.toUByte() to Color(0xFF9EE594),
        221u.toUByte() to Color(0xFF9DE7A0),
        222u.toUByte() to Color(0xFF9BECC2),
        223u.toUByte() to Color(0xFF95F6F2),
        224u.toUByte() to Color(0xFF99353F),
        225u.toUByte() to Color(0xFF983E5A),
        226u.toUByte() to Color(0xFF955694),
        227u.toUByte() to Color(0xFF8F74D2),
        228u.toUByte() to Color(0xFF9D5B4D),
        229u.toUByte() to Color(0xFF9D6064),
        230u.toUByte() to Color(0xFF9A7099),
        231u.toUByte() to Color(0xFF9587D5),
        232u.toUByte() to Color(0xFFAFA072),
        233u.toUByte() to Color(0xFFAEA382),
        234u.toUByte() to Color(0xFFABABAB),
        255u.toUByte() to Color(0xFFFFFFFF),
        235u.toUByte() to Color(0xFFA7BAE2),
        236u.toUByte() to Color(0xFFC9E89D),
        237u.toUByte() to Color(0xFFC9EAA7),
        238u.toUByte() to Color(0xFFC7F0C8),
        239u.toUByte() to Color(0xFFC3F9F7),
        240u.toUByte() to Color(0xFFE35462),
        241u.toUByte() to Color(0xFFE25874),
        242u.toUByte() to Color(0xFFE16AA3),
        243u.toUByte() to Color(0xFFDE83DC),
        244u.toUByte() to Color(0xFFE66E6B),
        245u.toUByte() to Color(0xFFE6727C),
        246u.toUByte() to Color(0xFFE37FA7),
        247u.toUByte() to Color(0xFFE194DF),
        248u.toUByte() to Color(0xFFF1AA86),
        249u.toUByte() to Color(0xFFF1AD93),
        250u.toUByte() to Color(0xFFEFB5B8),
        251u.toUByte() to Color(0xFFECC3EB),
        252u.toUByte() to Color(0xFFFFEEAB),
        253u.toUByte() to Color(0xFFFFF1B5),
        254u.toUByte() to Color(0xFFFFF6D3),
    )

fun pebbleToAndroidColor(pebbleColor: UByte): Color = PEBBLE_COLOR_MAP[pebbleColor] ?: Color.Black

private val APP_UUID = UUID.fromString("075a861e-c60b-4bb6-b3f2-b592925e86b1")
private const val MSG_POST_ALERTS = 0u

class PebbleManager(
    private val context: Context,
) : AutoCloseable {
    private val sender: PebbleSender = DefaultPebbleSender(context)

    override fun close() {
        sender.close()
    }

    suspend fun openAppOnWatch(): Boolean {
        val result = sender.startAppOnTheWatch(APP_UUID)
        return result?.values?.any { it == TransmissionResult.Success } == true
    }

    suspend fun postAlerts(alerts: List<Alert>) {
        require(alerts.size <= Alert.MAX_ALERTS) {
            "Cannot send more than ${Alert.MAX_ALERTS} alerts at once (attempted to send ${alerts.size})"
        }
        val dict =
            mutableMapOf<UInt, PebbleDictionaryItem>(
                0u to PebbleDictionaryItem.UInt32(MSG_POST_ALERTS),
                1u to PebbleDictionaryItem.UInt32(alerts.size.toUInt()),
            )
        val size = 9u
        val start = 2u
        alerts.forEachIndexed { i, alert ->
            val base = start + size * i.toUInt()
            dict[base + 0u] = PebbleDictionaryItem.UInt32(alert.id)
            dict[base + 1u] = PebbleDictionaryItem.Text(alert.calendarName)
            dict[base + 2u] = PebbleDictionaryItem.Text(alert.title)
            dict[base + 3u] = PebbleDictionaryItem.Text(alert.details)
            dict[base + 4u] = PebbleDictionaryItem.Text(alert.location)
            dict[base + 5u] = PebbleDictionaryItem.UInt32(alert.startTime.epochSecond.toUInt())
            dict[base + 6u] = PebbleDictionaryItem.UInt32(alert.endTime.epochSecond.toUInt())
            dict[base + 7u] = PebbleDictionaryItem.UInt32(alert.alertTime.epochSecond.toUInt())
            dict[base + 8u] = PebbleDictionaryItem.UInt8(alert.color)
        }
        sender.sendDataToPebble(APP_UUID, dict)
    }
}
