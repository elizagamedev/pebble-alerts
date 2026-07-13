package sh.eliza.pebble.calnotify

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class PebbleColor(
    private val value: Int,
) {
    fun toInt(): Int = value

    fun toAndroidColor(): Color {
        val r = ((value shr 4) and 0x3) * 85
        val g = ((value shr 2) and 0x3) * 85
        val b = (value and 0x3) * 85
        return Color(r, g, b)
    }

    fun toAndroidColorCorrected(): Color = PEBBLE_COLOR_CORRECTED_MAP[this] ?: Color.Black

    fun toUByte(): UByte = value.toUByte()

    companion object {
        val PEBBLE_COLORS =
            (192..255)
                .map { PebbleColor(it) }
                .map { color ->
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(
                        color.toAndroidColorCorrected().toArgb(),
                        hsv,
                    )
                    Pair(color, hsv)
                }.sortedWith(
                    compareBy<Pair<PebbleColor, FloatArray>> {
                        it.second[1] == 0f
                    } // grayscales at the end
                        .thenBy { it.second[0] } // hue
                        .thenBy { it.second[1] } // sat
                        .thenBy { it.second[2] }, // value
                ).map { it.first }

        fun fromRgb(androidColor: Int): PebbleColor {
            val r = (androidColor shr 16 and 0xFF) shr 6
            val g = (androidColor shr 8 and 0xFF) shr 6
            val b = (androidColor and 0xFF) shr 6
            return PebbleColor(0xC0 or (r shl 4) or (g shl 2) or b)
        }
    }
}

private val PEBBLE_COLOR_CORRECTED_MAP =
    mapOf<PebbleColor, Color>(
        PebbleColor(192) to Color(0xFF000000),
        PebbleColor(193) to Color(0xFF001E41),
        PebbleColor(194) to Color(0xFF004387),
        PebbleColor(195) to Color(0xFF0068CA),
        PebbleColor(196) to Color(0xFF2B4A2C),
        PebbleColor(197) to Color(0xFF27514F),
        PebbleColor(198) to Color(0xFF16638D),
        PebbleColor(199) to Color(0xFF007DCE),
        PebbleColor(200) to Color(0xFF5E9860),
        PebbleColor(201) to Color(0xFF5C9B72),
        PebbleColor(202) to Color(0xFF57A5A2),
        PebbleColor(203) to Color(0xFF4CB4DB),
        PebbleColor(204) to Color(0xFF8EE391),
        PebbleColor(205) to Color(0xFF8EE69E),
        PebbleColor(206) to Color(0xFF8AEBC0),
        PebbleColor(207) to Color(0xFF84F5F1),
        PebbleColor(208) to Color(0xFF4A161B),
        PebbleColor(209) to Color(0xFF482748),
        PebbleColor(210) to Color(0xFF40488A),
        PebbleColor(211) to Color(0xFF2F6BCC),
        PebbleColor(212) to Color(0xFF564E36),
        PebbleColor(213) to Color(0xFF545454),
        PebbleColor(214) to Color(0xFF4F6790),
        PebbleColor(215) to Color(0xFF4180D0),
        PebbleColor(216) to Color(0xFF759A64),
        PebbleColor(217) to Color(0xFF759D76),
        PebbleColor(218) to Color(0xFF71A6A4),
        PebbleColor(219) to Color(0xFF69B5DD),
        PebbleColor(220) to Color(0xFF9EE594),
        PebbleColor(221) to Color(0xFF9DE7A0),
        PebbleColor(222) to Color(0xFF9BECC2),
        PebbleColor(223) to Color(0xFF95F6F2),
        PebbleColor(224) to Color(0xFF99353F),
        PebbleColor(225) to Color(0xFF983E5A),
        PebbleColor(226) to Color(0xFF955694),
        PebbleColor(227) to Color(0xFF8F74D2),
        PebbleColor(228) to Color(0xFF9D5B4D),
        PebbleColor(229) to Color(0xFF9D6064),
        PebbleColor(230) to Color(0xFF9A7099),
        PebbleColor(231) to Color(0xFF9587D5),
        PebbleColor(232) to Color(0xFFAFA072),
        PebbleColor(233) to Color(0xFFAEA382),
        PebbleColor(234) to Color(0xFFABABAB),
        PebbleColor(235) to Color(0xFFA7BAE2),
        PebbleColor(236) to Color(0xFFC9E89D),
        PebbleColor(237) to Color(0xFFC9EAA7),
        PebbleColor(238) to Color(0xFFC7F0C8),
        PebbleColor(239) to Color(0xFFC3F9F7),
        PebbleColor(240) to Color(0xFFE35462),
        PebbleColor(241) to Color(0xFFE25874),
        PebbleColor(242) to Color(0xFFE16AA3),
        PebbleColor(243) to Color(0xFFDE83DC),
        PebbleColor(244) to Color(0xFFE66E6B),
        PebbleColor(245) to Color(0xFFE6727C),
        PebbleColor(246) to Color(0xFFE37FA7),
        PebbleColor(247) to Color(0xFFE194DF),
        PebbleColor(248) to Color(0xFFF1AA86),
        PebbleColor(249) to Color(0xFFF1AD93),
        PebbleColor(250) to Color(0xFFEFB5B8),
        PebbleColor(251) to Color(0xFFECC3EB),
        PebbleColor(252) to Color(0xFFFFEEAB),
        PebbleColor(253) to Color(0xFFFFF1B5),
        PebbleColor(254) to Color(0xFFFFF6D3),
        PebbleColor(255) to Color(0xFFFFFFFF),
    )
