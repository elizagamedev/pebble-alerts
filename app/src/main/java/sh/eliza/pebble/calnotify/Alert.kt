package sh.eliza.pebble.calnotify

import android.Manifest
import android.content.ContentUris
import android.content.Context
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
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

data class Alert(
    val id: UInt,
    val calendarName: String,
    val title: String,
    val details: String,
    val location: String,
    val startTime: Instant,
    val endTime: Instant,
    val alertTime: Instant,
    val color: PebbleColor,
) {
    companion object {
        fun getUpcomingAlerts(
            context: Context,
            appSettings: AppSettings,
        ): Sequence<Alert> =
            (
                getUpcomingCalendarAlerts(context, appSettings.calendarSettings) +
                    getUpcomingContactAlerts(context, appSettings.contactSettings)
            ).sortedWith(compareBy({ it.alertTime }, { it.id }))

        fun getUpcomingCalendarAlerts(
            context: Context,
            calendarSettings: Map<Long, CalendarSettings>,
        ): Sequence<Alert> = getUpcomingCalendarAlertsInternal(context, calendarSettings)

        fun getUpcomingContactAlerts(
            context: Context,
            contactSettings: Map<ContactEventType, ContactSettings>,
        ): Sequence<Alert> = getUpcomingContactAlertsInternal(context, contactSettings)
    }
}

// --- ID Generation ---
private const val ID_DOMAIN_CONTACT = 0x80000000u
private const val ID_CONTACT_BIRTHDAY = 0x40000000u

private const val ID_MASK = 0x7FFFFFFFu
private const val ID_CONTACT_MASK = 0x3FFFFFFFu

private fun makeCalendarId(eventId: Long): UInt = eventId.toUInt() and ID_MASK

private fun makeContactId(
    contactId: Long,
    type: ContactEventType,
): UInt {
    var id = ID_DOMAIN_CONTACT
    if (type == ContactEventType.BIRTHDAY) id = id or ID_CONTACT_BIRTHDAY
    return id or (contactId.toUInt() and ID_CONTACT_MASK)
}

private fun getUpcomingCalendarAlertsInternal(
    context: Context,
    calendarSettings: Map<Long, CalendarSettings>,
): Sequence<Alert> =
    sequence {
        if (calendarSettings.isEmpty()) return@sequence
        val enabledCalIds = calendarSettings.filterValues { it.enabled }.keys
        if (enabledCalIds.isEmpty()) return@sequence
        if (!context.hasPermission(Manifest.permission.READ_CALENDAR)) return@sequence

        val now = Instant.now()
        val searchStart = now.minus(1, ChronoUnit.DAYS)
        val end = now.plus(7, ChronoUnit.DAYS)

        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, searchStart.toEpochMilli())
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
                Instances.ALL_DAY,
                Instances.CALENDAR_ID,
            )

        val calIds = enabledCalIds.joinToString(",")

        context.contentResolver
            .query(
                builder.build(),
                projection,
                "${Instances.CALENDAR_ID} IN ($calIds)",
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
                val allDayIdx = cursor.getColumnIndexOrThrow(Instances.ALL_DAY)
                val calIdIdx = cursor.getColumnIndexOrThrow(Instances.CALENDAR_ID)

                while (cursor.moveToNext()) {
                    val calId = cursor.getLong(calIdIdx)
                    val config = calendarSettings[calId]
                    if (config == null || !config.enabled) continue

                    val isAllDay = cursor.getInt(allDayIdx) != 0

                    val startInstant = Instant.ofEpochMilli(cursor.getLong(startIdx))
                    val endInstant = Instant.ofEpochMilli(cursor.getLong(endIdx))
                    val eventId = cursor.getLong(eventIdIdx)
                    val calendarName = cursor.getString(calNameIdx) ?: ""
                    val title =
                        cursor.getString(titleIdx)?.takeUnless { it.isBlank() } ?: "Untitled"
                    val details = cursor.getString(detailsIdx) ?: ""
                    val location = cursor.getString(locIdx) ?: ""
                    val color = cursor.getInt(colorIdx)

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
                                        reminderCursor.getInt(0).minutes
                                    } else {
                                        null
                                    }
                                }.toList()
                            }.orEmpty()

                    yieldAll(
                        if (isAllDay) {
                            createAllDayCalendarAlerts(
                                config,
                                eventId = eventId,
                                calendarName = calendarName,
                                title = title,
                                details = details,
                                location = location,
                                startDate = startInstant.atZone(ZoneOffset.UTC).toLocalDate(),
                                endDate = endInstant.atZone(ZoneOffset.UTC).toLocalDate(),
                                color = color,
                                offsets = offsets,
                            )
                        } else {
                            createTimedCalendarAlerts(
                                config,
                                eventId = eventId,
                                calendarName = calendarName,
                                title = title,
                                details = details,
                                location = location,
                                startTime = startInstant,
                                endTime = endInstant,
                                color = color,
                                offsets = offsets,
                            )
                        },
                    )
                }
            }
    }

private fun createAllDayCalendarAlerts(
    config: CalendarSettings,
    eventId: Long,
    calendarName: String,
    title: String,
    details: String,
    location: String,
    startDate: LocalDate,
    endDate: LocalDate,
    color: Int,
    offsets: List<Duration>,
): Sequence<Alert> =
    sequence {
        val startTime = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endTime = endDate.atStartOfDay(ZoneId.systemDefault()).toInstant()

        if (offsets.isNotEmpty()) {
            for (offset in offsets) {
                yield(
                    Alert(
                        id = makeCalendarId(eventId),
                        calendarName = calendarName,
                        title = title,
                        details = details,
                        location = location,
                        startTime = startTime,
                        endTime = endTime,
                        alertTime = startTime - offset.toJavaDuration(),
                        color = PebbleColor.fromRgb(color),
                    ),
                )
            }
        }

        val days =
            if (ChronoUnit.DAYS.between(startDate, endDate) <= 1) {
                listOf(startDate)
            } else {
                when (config.multiDayMode) {
                    MultiDayAlertMode.OFF -> {
                        emptyList()
                    }

                    MultiDayAlertMode.FIRST_DAY -> {
                        listOf(startDate)
                    }

                    MultiDayAlertMode.EVERY_DAY -> {
                        generateSequence(startDate) { it.plusDays(1) }
                            .takeWhile { it.isBefore(endDate) }
                            .toList()
                    }
                }
            }

        if (offsets.isEmpty() && config.dayBefore) {
            days.firstOrNull()?.let { firstDay ->
                val alertTime =
                    firstDay
                        .minusDays(1)
                        .atTime(config.dayBeforeTime)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()

                yield(
                    Alert(
                        id = makeCalendarId(eventId),
                        calendarName = calendarName,
                        title = "Tomorrow: $title",
                        details = details,
                        location = location,
                        startTime = startTime,
                        endTime = endTime,
                        alertTime = alertTime,
                        color = PebbleColor.fromRgb(color),
                    ),
                )
            }
        }

        if (config.dayOf) {
            for (day in days) {
                val alertTime =
                    day
                        .atTime(
                            config.dayOfTime,
                        ).atZone(ZoneId.systemDefault())
                        .toInstant()
                yield(
                    Alert(
                        id = makeCalendarId(eventId),
                        calendarName = calendarName,
                        title = "Today: $title",
                        details = details,
                        location = location,
                        startTime = startTime,
                        endTime = endTime,
                        alertTime = alertTime,
                        color = PebbleColor.fromRgb(color),
                    ),
                )
            }
        }
    }

private fun createTimedCalendarAlerts(
    config: CalendarSettings,
    eventId: Long,
    calendarName: String,
    title: String,
    details: String,
    location: String,
    startTime: Instant,
    endTime: Instant,
    color: Int,
    offsets: List<Duration>,
): Sequence<Alert> =
    sequence {
        val finalOffsets =
            offsets.ifEmpty {
                listOfNotNull(config.unremindedOffset)
            }

        for (offset in finalOffsets) {
            yield(
                Alert(
                    id = makeCalendarId(eventId),
                    calendarName = calendarName,
                    title = title,
                    details = details,
                    location = location,
                    startTime = startTime,
                    endTime = endTime,
                    alertTime = startTime - offset.toJavaDuration(),
                    color = PebbleColor.fromRgb(color),
                ),
            )
        }
    }

private fun getUpcomingContactAlertsInternal(
    context: Context,
    contactSettings: Map<ContactEventType, ContactSettings>,
): Sequence<Alert> =
    sequence {
        if (contactSettings.isEmpty()) return@sequence
        if (!contactSettings.values.any { it.dayOf || it.dayBefore }) {
            return@sequence
        }
        if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return@sequence

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
                    val name = cursor.getString(nameIdx) ?: continue
                    val dateStr = cursor.getString(dateIdx) ?: continue
                    val type =
                        when (cursor.getInt(typeIdx)) {
                            TYPE_BIRTHDAY -> ContactEventType.BIRTHDAY
                            TYPE_ANNIVERSARY -> ContactEventType.ANNIVERSARY
                            else -> null
                        } ?: continue

                    val config = contactSettings[type]
                    if (config == null || (!config.dayOf && !config.dayBefore)) {
                        continue
                    }

                    yieldAll(
                        createFromContactInstance(
                            config,
                            today,
                            zone,
                            contactId = cursor.getLong(idIdx),
                            name = name,
                            dateStr = dateStr,
                            type = type,
                        ),
                    )
                }
            }
    }

private fun createFromContactInstance(
    config: ContactSettings,
    today: LocalDate,
    zone: ZoneId,
    contactId: Long,
    name: String,
    dateStr: String,
    type: ContactEventType,
): Sequence<Alert> =
    sequence {
        var originalYear: Int? = null
        val parsedMonthDay =
            try {
                if (dateStr.startsWith("--")) {
                    MonthDay.parse(dateStr)
                } else {
                    val ld = LocalDate.parse(dateStr)
                    originalYear = ld.year
                    MonthDay.of(ld.month, ld.dayOfMonth)
                }
            } catch (e: DateTimeParseException) {
                return@sequence
            }

        var nextOccurrence = parsedMonthDay.atYear(today.year)
        if (nextOccurrence.isBefore(today)) {
            // If their birthday already passed this year, it's next year
            // Java Time gracefully handles Leap Year fallback for
            // MonthDay.atYear
            nextOccurrence = parsedMonthDay.atYear(today.year + 1)
        }

        val years = originalYear?.let { nextOccurrence.year - it }

        val title =
            when (type) {
                ContactEventType.BIRTHDAY -> "$name's Birthday"
                ContactEventType.ANNIVERSARY -> "$name's Anniversary"
            }

        val calName =
            when (type) {
                ContactEventType.BIRTHDAY -> "Birthdays"
                ContactEventType.ANNIVERSARY -> "Anniversaries"
            }

        val commonStartTime = nextOccurrence.atStartOfDay(zone).toInstant()
        val commonEndTime =
            nextOccurrence
                .plusDays(
                    1,
                ).atStartOfDay(zone)
                .toInstant()

        suspend fun SequenceScope<Alert>.addAlertIfValid(
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

            val finalTitle = if (isDayBefore) "Tomorrow: $title" else "Today: $title"

            val detailsText =
                if (years != null) {
                    val yearsStr = "$years year${if (years == 1) "" else "s"}"
                    if (type == ContactEventType.BIRTHDAY) {
                        if (isDayBefore) {
                            "$name will be $yearsStr old tomorrow!"
                        } else {
                            "$name is $yearsStr old today!"
                        }
                    } else {
                        if (isDayBefore) {
                            "It will be $yearsStr tomorrow!"
                        } else {
                            "It's been $yearsStr!"
                        }
                    }
                } else {
                    ""
                }

            yield(
                Alert(
                    id = makeContactId(contactId, type),
                    calendarName = calName,
                    title = finalTitle,
                    details = detailsText,
                    location = "",
                    startTime = commonStartTime,
                    endTime = commonEndTime,
                    alertTime = alertTime,
                    color = config.color,
                ),
            )
        }

        addAlertIfValid(if (config.dayBefore) config.dayBeforeTime else null, true)
        addAlertIfValid(if (config.dayOf) config.dayOfTime else null, false)
    }
