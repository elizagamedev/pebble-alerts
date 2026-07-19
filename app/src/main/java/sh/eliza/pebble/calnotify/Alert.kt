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
import android.text.format.DateUtils
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

private object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeLong(value.toEpochMilli())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}

typealias OnAllDayCalendarEvent<T> = (
    config: CalendarSettings,
    id: UInt,
    calendarName: String,
    title: String,
    details: String,
    location: String,
    startDate: LocalDate,
    endDate: LocalDate,
    color: Int,
    offsets: List<Duration>,
) -> Sequence<T>

typealias OnTimedCalendarEvent<T> = (
    config: CalendarSettings,
    id: UInt,
    calendarName: String,
    title: String,
    details: String,
    location: String,
    startTime: Instant,
    endTime: Instant,
    color: Int,
    offsets: List<Duration>,
) -> Sequence<T>

typealias OnContactEvent<T> = (
    config: ContactSettings,
    id: UInt,
    calendarName: String,
    title: String,
    dayOfDetails: String,
    dayBeforeDetails: String,
    startTime: Instant,
    subtitle: String?,
) -> Sequence<T>

@Serializable
data class Alert(
    val id: UInt,
    val calendarName: String,
    val title: String,
    val details: String,
    val location: String,
    @Serializable(with = InstantSerializer::class) val startTime: Instant,
    @Serializable(with = InstantSerializer::class) val endTime: Instant,
    @Serializable(with = InstantSerializer::class) val alertTime: Instant,
    val color: PebbleColor,
    val allDay: Boolean,
) {
    companion object {
        const val MAX_WATCH_ALERTS = 6

        fun getUpcomingAlerts(
            context: Context,
            appSettings: AppSettings,
        ): List<Alert> =
            (
                getUpcomingCalendarAlerts(context, appSettings.calendarSettings) +
                    getUpcomingContactAlerts(context, appSettings.contactSettings)
            ).sortedWith(compareBy({ it.alertTime }, { it.id })).toList()

        fun getUpcomingCalendarAlerts(
            context: Context,
            calendarSettings: Map<Long, CalendarSettings>,
        ): Sequence<Alert> =
            visitUpcomingCalendarEventsInternal(
                context,
                calendarSettings,
                onAllDayEvent = ::createAllDayCalendarAlerts,
                onTimedEvent = ::createTimedCalendarAlerts,
            )

        fun getUpcomingContactAlerts(
            context: Context,
            contactSettings: Map<ContactEventType, ContactSettings>,
        ): Sequence<Alert> =
            visitUpcomingContactEventsInternal(
                context,
                contactSettings,
                onContactEvent = ::createFromContactInstance,
            )

        fun <T> visitUpcomingContactEvents(
            context: Context,
            contactSettings: Map<ContactEventType, ContactSettings>,
            onContactEvent: OnContactEvent<T>,
        ): Sequence<T> =
            visitUpcomingContactEventsInternal(
                context,
                contactSettings,
                onContactEvent,
            )
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

private fun <T> visitUpcomingCalendarEventsInternal(
    context: Context,
    calendarSettings: Map<Long, CalendarSettings>,
    onAllDayEvent: OnAllDayCalendarEvent<T>,
    onTimedEvent: OnTimedCalendarEvent<T>,
): Sequence<T> =
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
                        cursor.getString(titleIdx)?.takeUnless { it.isBlank() }
                            ?: context.getString(R.string.alert_untitled)
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

                    val id = makeCalendarId(eventId)

                    yieldAll(
                        if (isAllDay) {
                            onAllDayEvent(
                                config,
                                id,
                                calendarName,
                                title,
                                details,
                                location,
                                startInstant.atZone(ZoneOffset.UTC).toLocalDate(),
                                endInstant.atZone(ZoneOffset.UTC).toLocalDate(),
                                color,
                                offsets,
                            )
                        } else {
                            onTimedEvent(
                                config,
                                id,
                                calendarName,
                                title,
                                details,
                                location,
                                startInstant,
                                endInstant,
                                color,
                                offsets,
                            )
                        },
                    )
                }
            }
    }

private fun createAllDayCalendarAlerts(
    config: CalendarSettings,
    id: UInt,
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
                        id = id,
                        calendarName = calendarName,
                        title = title,
                        details = details,
                        location = location,
                        startTime = startTime,
                        endTime = endTime,
                        alertTime = startTime - offset.toJavaDuration(),
                        color = PebbleColor.fromRgb(color),
                        allDay = true,
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
                        id = id,
                        calendarName = calendarName,
                        title = title,
                        details = details,
                        location = location,
                        startTime = startTime,
                        endTime = endTime,
                        alertTime = alertTime,
                        color = PebbleColor.fromRgb(color),
                        allDay = true,
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
                        id = id,
                        calendarName = calendarName,
                        title = title,
                        details = details,
                        location = location,
                        startTime = startTime,
                        endTime = endTime,
                        alertTime = alertTime,
                        color = PebbleColor.fromRgb(color),
                        allDay = true,
                    ),
                )
            }
        }
    }

private fun createTimedCalendarAlerts(
    config: CalendarSettings,
    id: UInt,
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
        val finalOffsets = offsets.ifEmpty { listOfNotNull(config.unremindedOffset) }

        for (offset in finalOffsets) {
            yield(
                Alert(
                    id = id,
                    calendarName = calendarName,
                    title = title,
                    details = details,
                    location = location,
                    startTime = startTime,
                    endTime = endTime,
                    alertTime = startTime - offset.toJavaDuration(),
                    color = PebbleColor.fromRgb(color),
                    allDay = false,
                ),
            )
        }
    }

private fun <T> visitUpcomingContactEventsInternal(
    context: Context,
    contactSettings: Map<ContactEventType, ContactSettings>,
    onContactEvent: OnContactEvent<T>,
): Sequence<T> =
    sequence {
        if (contactSettings.isEmpty()) return@sequence
        if (!contactSettings.values.any { it.dayOf || it.dayBefore || it.timelinePins }) {
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
                    if (config == null ||
                        (!config.dayOf && !config.dayBefore && !config.timelinePins)
                    ) {
                        continue
                    }

                    var startingYear: Int? = null
                    val parsedMonthDay =
                        try {
                            if (dateStr.startsWith("--")) {
                                MonthDay.parse(dateStr)
                            } else {
                                val ld = LocalDate.parse(dateStr)
                                startingYear = ld.year
                                MonthDay.of(ld.month, ld.dayOfMonth)
                            }
                        } catch (e: DateTimeParseException) {
                            continue
                        }

                    var nextOccurrence = parsedMonthDay.atYear(today.year)
                    if (nextOccurrence.isBefore(today)) {
                        // If their birthday already passed this year, it's next year. Java Time
                        // gracefully handles Leap Year fallback for MonthDay.atYear.
                        nextOccurrence = parsedMonthDay.atYear(today.year + 1)
                    }

                    // If it's more than 6 months out, consider the one that passed recently
                    // instead. This keeps past events (like yesterday's birthday) visible in the
                    // timeline/watchapp.
                    if (ChronoUnit.MONTHS.between(today, nextOccurrence) > 6) {
                        nextOccurrence = parsedMonthDay.atYear(nextOccurrence.year - 1)
                    }

                    val id = makeContactId(cursor.getLong(idIdx), type)

                    val title =
                        when (type) {
                            ContactEventType.BIRTHDAY -> {
                                context.getString(
                                    R.string.alert_title_birthday,
                                    name,
                                )
                            }

                            ContactEventType.ANNIVERSARY -> {
                                context.getString(
                                    R.string.alert_title_anniversary,
                                    name,
                                )
                            }
                        }

                    val formattedDate =
                        DateUtils.formatDateTime(
                            context,
                            nextOccurrence.atStartOfDay(zone).toInstant().toEpochMilli(),
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
                        )

                    val years = startingYear?.let { nextOccurrence.year - it }
                    val dayOfDetails =
                        if (years != null) {
                            if (type == ContactEventType.BIRTHDAY) {
                                context.resources.getQuantityString(
                                    R.plurals.alert_birthday_today_years,
                                    years,
                                    formattedDate,
                                    name,
                                    years,
                                )
                            } else {
                                context.resources.getQuantityString(
                                    R.plurals.alert_anniversary_today_years,
                                    years,
                                    formattedDate,
                                    years,
                                )
                            }
                        } else {
                            context.getString(R.string.alert_contact_today, formattedDate)
                        }

                    val dayBeforeDetails =
                        if (years != null) {
                            if (type == ContactEventType.BIRTHDAY) {
                                context.resources.getQuantityString(
                                    R.plurals.alert_birthday_tomorrow_years,
                                    years,
                                    formattedDate,
                                    name,
                                    years,
                                )
                            } else {
                                context.resources.getQuantityString(
                                    R.plurals.alert_anniversary_tomorrow_years,
                                    years,
                                    formattedDate,
                                    years,
                                )
                            }
                        } else {
                            context.getString(R.string.alert_contact_tomorrow, formattedDate)
                        }

                    val calName =
                        when (type) {
                            ContactEventType.BIRTHDAY -> {
                                context.getString(
                                    R.string.contact_event_type_birthday,
                                )
                            }

                            ContactEventType.ANNIVERSARY -> {
                                context.getString(
                                    R.string.contact_event_type_anniversary,
                                )
                            }
                        }

                    val subtitle =
                        years?.let {
                            if (type == ContactEventType.BIRTHDAY) {
                                context.resources.getQuantityString(
                                    R.plurals.timeline_subtitle_birthday_years,
                                    it,
                                    it,
                                )
                            } else {
                                context.resources.getQuantityString(
                                    R.plurals.timeline_subtitle_anniversary_years,
                                    it,
                                    it,
                                )
                            }
                        }

                    yieldAll(
                        onContactEvent(
                            config,
                            id,
                            calName,
                            title,
                            dayOfDetails,
                            dayBeforeDetails,
                            nextOccurrence.atStartOfDay(zone).toInstant(),
                            subtitle,
                        ),
                    )
                }
            }
    }

private fun createFromContactInstance(
    config: ContactSettings,
    id: UInt,
    calendarName: String,
    title: String,
    dayOfDetails: String,
    dayBeforeDetails: String,
    startTime: Instant,
    subtitle: String?,
): Sequence<Alert> =
    sequence {
        val commonEndTime = startTime.plus(1, ChronoUnit.DAYS)

        suspend fun SequenceScope<Alert>.addAlertIfValid(
            time: LocalTime?,
            isDayBefore: Boolean,
        ) {
            if (time == null) return
            val alertDate = startTime.atZone(ZoneId.systemDefault()).toLocalDate()
            val alertTime =
                if (isDayBefore) {
                    alertDate
                        .minusDays(1)
                        .atTime(time)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                } else {
                    alertDate.atTime(time).atZone(ZoneId.systemDefault()).toInstant()
                }

            val detailsText =
                if (isDayBefore) {
                    dayBeforeDetails
                } else {
                    dayOfDetails
                }

            yield(
                Alert(
                    id = id,
                    calendarName = calendarName,
                    title = title,
                    details = detailsText,
                    location = "",
                    startTime = startTime,
                    endTime = commonEndTime,
                    alertTime = alertTime,
                    color = config.color,
                    allDay = true,
                ),
            )
        }

        addAlertIfValid(if (config.dayBefore) config.dayBeforeTime else null, true)
        addAlertIfValid(if (config.dayOf) config.dayOfTime else null, false)
    }
