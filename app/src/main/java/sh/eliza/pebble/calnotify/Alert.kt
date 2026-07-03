package sh.eliza.pebble.calnotify

import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY
import android.provider.ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class ContactAlertConfig(
    val timeOnDay: LocalTime?,
    val timeDayBefore: LocalTime?,
    val color: UByte,
)

data class Alert(
    val id: UInt,
    val calendarName: String,
    val title: String,
    val details: String,
    val location: String,
    val startTime: Instant,
    val endTime: Instant,
    val alertTime: Instant,
    val color: UByte,
) {
    companion object {
        val PEBBLE_COLORS =
            listOf(
                0b11110000u.toUByte(), // Red
                0b11001100u.toUByte(), // Green
                0b11000011u.toUByte(), // Blue
                0b11110011u.toUByte(), // Magenta
                0b11001111u.toUByte(), // Cyan
                0b11111100u.toUByte(), // Yellow
                0b11111000u.toUByte(), // Orange
                0b11111111u.toUByte(), // White
            )

        const val MAX_ALERTS = 8

        fun getUpcomingCalendarAlerts(
            context: Context,
            defaultAlertOffsetMinutes: Int? = null,
        ): List<Alert> {
            val alerts = mutableListOf<Alert>()
            val now = Instant.now()
            val end = now.plus(7, ChronoUnit.DAYS)

            val builder = Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, now.toEpochMilli())
            ContentUris.appendId(builder, end.toEpochMilli())

            val projection =
                arrayOf(
                    Instances.EVENT_ID,
                    Instances.TITLE,
                    Instances.DESCRIPTION,
                    Instances.EVENT_LOCATION,
                    Instances.BEGIN,
                    Instances.END,
                    Instances.DISPLAY_COLOR,
                    Calendars.CALENDAR_DISPLAY_NAME,
                )

            context.contentResolver
                .query(
                    builder.build(),
                    projection,
                    "${Instances.VISIBLE} = 1",
                    null,
                    "${Instances.BEGIN} ASC",
                )?.use { cursor ->
                    val eventIdIdx = cursor.getColumnIndexOrThrow(Instances.EVENT_ID)
                    val titleIdx = cursor.getColumnIndexOrThrow(Instances.TITLE)
                    val detailsIdx = cursor.getColumnIndexOrThrow(Instances.DESCRIPTION)
                    val locIdx = cursor.getColumnIndexOrThrow(Instances.EVENT_LOCATION)
                    val startIdx = cursor.getColumnIndexOrThrow(Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(Instances.END)
                    val colorIdx = cursor.getColumnIndexOrThrow(Instances.DISPLAY_COLOR)
                    val calNameIdx = cursor.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val eventId = cursor.getLong(eventIdIdx)

                        val offsets =
                            context.contentResolver
                                .query(
                                    Reminders.CONTENT_URI,
                                    arrayOf(Reminders.MINUTES),
                                    "${Reminders.EVENT_ID} = ?",
                                    arrayOf(eventId.toString()),
                                    null,
                                )?.use { reminderCursor ->
                                    generateSequence {
                                        if (reminderCursor.moveToNext()) {
                                            reminderCursor.getInt(0)
                                        } else {
                                            null
                                        }
                                    }.toList()
                                }.orEmpty()
                                .ifEmpty {
                                    listOfNotNull(defaultAlertOffsetMinutes)
                                }

                        if (offsets.isNotEmpty()) {
                            val calendarName = cursor.getString(calNameIdx) ?: ""
                            val title = cursor.getString(titleIdx) ?: ""
                            val details = cursor.getString(detailsIdx) ?: ""
                            val location = cursor.getString(locIdx) ?: ""
                            val startTime = Instant.ofEpochMilli(cursor.getLong(startIdx))
                            val endTime = Instant.ofEpochMilli(cursor.getLong(endIdx))
                            val color = cursor.getInt(colorIdx)

                            for (offset in offsets) {
                                alerts.add(
                                    Alert(
                                        id = eventId.toUInt(),
                                        calendarName = calendarName,
                                        title = title,
                                        details = details,
                                        location = location,
                                        startTime = startTime,
                                        endTime = endTime,
                                        alertTime =
                                            startTime.minus(
                                                offset.toLong(),
                                                ChronoUnit.MINUTES,
                                            ),
                                        color = androidToPebbleColor(color),
                                    ),
                                )
                            }
                        }
                    }
                }

            // Re-sort the final payload by alert time instead of event start time
            alerts.sortBy { it.alertTime }
            return alerts
        }

        fun getUpcomingContactAlerts(
            context: Context,
            birthdayConfig: ContactAlertConfig,
            anniversaryConfig: ContactAlertConfig,
        ): List<Alert> {
            if (birthdayConfig.timeOnDay == null && birthdayConfig.timeDayBefore == null &&
                anniversaryConfig.timeOnDay == null &&
                anniversaryConfig.timeDayBefore == null
            ) {
                return listOf()
            }

            val alerts = mutableListOf<Alert>()

            val uri = ContactsContract.Data.CONTENT_URI
            val projection =
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Event.START_DATE,
                    ContactsContract.CommonDataKinds.Event.TYPE,
                )
            val selection =
                "${ContactsContract.Data.MIMETYPE} = ? " +
                    "AND ${ContactsContract.CommonDataKinds.Event.TYPE} IN (?, ?)"
            val selectionArgs =
                arrayOf(
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                    TYPE_BIRTHDAY.toString(),
                    TYPE_ANNIVERSARY.toString(),
                )

            context.contentResolver
                .query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                    val nameIdx =
                        cursor.getColumnIndexOrThrow(
                            ContactsContract.Contacts.DISPLAY_NAME,
                        )
                    val dateIdx =
                        cursor.getColumnIndexOrThrow(
                            ContactsContract.CommonDataKinds.Event.START_DATE,
                        )
                    val typeIdx =
                        cursor.getColumnIndexOrThrow(
                            ContactsContract.CommonDataKinds.Event.TYPE,
                        )

                    val today = LocalDate.now()
                    val zone = ZoneId.systemDefault()

                    while (cursor.moveToNext()) {
                        val contactId = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: continue
                        val dateStr = cursor.getString(dateIdx) ?: continue
                        val type = cursor.getInt(typeIdx)

                        val config =
                            if (type == TYPE_BIRTHDAY) {
                                birthdayConfig
                            } else {
                                anniversaryConfig
                            }
                        if (config.timeOnDay == null && config.timeDayBefore == null) {
                            continue
                        }

                        val parsedMonthDay =
                            try {
                                if (dateStr.startsWith("--")) {
                                    MonthDay.parse(dateStr)
                                } else {
                                    val ld = LocalDate.parse(dateStr)
                                    MonthDay.of(ld.month, ld.dayOfMonth)
                                }
                            } catch (e: DateTimeParseException) {
                                continue
                            }

                        var nextOccurrence = parsedMonthDay.atYear(today.year)
                        if (nextOccurrence.isBefore(today)) {
                            // If their birthday already passed this year, it's next year
                            // Java Time gracefully handles Leap Year fallback for
                            // MonthDay.atYear
                            nextOccurrence = parsedMonthDay.atYear(today.year + 1)
                        }

                        val title =
                            if (type == TYPE_BIRTHDAY) {
                                "$name's Birthday"
                            } else {
                                "$name's Anniversary"
                            }

                        val calName =
                            if (type == TYPE_BIRTHDAY) {
                                "Birthdays"
                            } else {
                                "Anniversaries"
                            }

                        val commonStartTime = nextOccurrence.atStartOfDay(zone).toInstant()
                        val commonEndTime =
                            nextOccurrence
                                .plusDays(
                                    1,
                                ).atStartOfDay(zone)
                                .toInstant()

                        fun addAlertIfValid(
                            time: LocalTime?,
                            isDayBefore: Boolean,
                        ) {
                            if (time == null) return
                            val alertTime =
                                if (isDayBefore) {
                                    nextOccurrence
                                        .minusDays(
                                            1,
                                        ).atTime(time)
                                        .atZone(zone)
                                        .toInstant()
                                } else {
                                    nextOccurrence.atTime(time).atZone(zone).toInstant()
                                }

                            if (alertTime.isAfter(Instant.now())) {
                                alerts.add(
                                    Alert(
                                        id =
                                            (1u shl 31) or
                                                ((if (isDayBefore) 1u else 0u) shl 30) or
                                                ((if (type == TYPE_BIRTHDAY) 1u else 0u) shl 29) or
                                                (contactId.toUInt() and 0x1FFFFFFFu),
                                        calendarName = calName,
                                        title = title,
                                        details = if (isDayBefore) "Tomorrow" else "Today",
                                        location = "",
                                        startTime = commonStartTime,
                                        endTime = commonEndTime,
                                        alertTime = alertTime,
                                        color = config.color,
                                    ),
                                )
                            }
                        }

                        addAlertIfValid(config.timeDayBefore, true)
                        addAlertIfValid(config.timeOnDay, false)
                    }
                }

            alerts.sortBy { it.alertTime }
            return alerts
        }
    }
}
