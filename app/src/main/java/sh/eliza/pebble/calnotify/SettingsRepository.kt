package sh.eliza.pebble.calnotify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val CALENDAR_ENABLE_REGEX = Regex("""^calendar_(\d+)_enable$""")

private const val DEFAULT_DAY_OF_TIME = 9 * 3600 // 9:00 AM
private const val DEFAULT_DAY_BEFORE_TIME = 18 * 3600 // 6:00 PM
private const val DEFAULT_UNREMINDED_MINUTES = 10

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class ContactAlertConfig(
    val timeOnDay: LocalTime?,
    val timeDayBefore: LocalTime?,
    val color: UByte,
) {
    companion object {
        fun createFromSettings(settings: ContactSettings): ContactAlertConfig =
            ContactAlertConfig(
                timeOnDay =
                    if (settings.dayOf) {
                        LocalTime.ofSecondOfDay(
                            settings.dayOfTime.toLong(),
                        )
                    } else {
                        null
                    },
                timeDayBefore =
                    if (settings.dayBefore) {
                        LocalTime.ofSecondOfDay(
                            settings.dayBeforeTime.toLong(),
                        )
                    } else {
                        null
                    },
                color = settings.color.toUByte(),
            )
    }
}

enum class MultiDayAlertMode(
    val label: String,
) {
    OFF("Off"),
    FIRST_DAY("Alert on first day"),
    EVERY_DAY("Alert every day"),
}

data class CalendarAlertConfig(
    val defaultAlertOffsetMinutes: Int?,
    val timeOnDay: LocalTime?,
    val timeDayBefore: LocalTime?,
    val multiDayMode: MultiDayAlertMode = MultiDayAlertMode.OFF,
) {
    companion object {
        fun createFromSettings(settings: CalendarSettings): CalendarAlertConfig =
            CalendarAlertConfig(
                defaultAlertOffsetMinutes =
                    if (settings.notifyUnreminded) settings.unremindedMinutes else null,
                timeOnDay =
                    if (settings.dayOf) {
                        LocalTime.ofSecondOfDay(
                            settings.dayOfTime.toLong(),
                        )
                    } else {
                        null
                    },
                timeDayBefore =
                    if (settings.dayBefore) {
                        LocalTime.ofSecondOfDay(
                            settings.dayBeforeTime.toLong(),
                        )
                    } else {
                        null
                    },
                multiDayMode = settings.multiDayMode,
            )
    }
}

enum class ContactEventType(
    val label: String,
) {
    BIRTHDAY("Birthdays"),
    ANNIVERSARY("Anniversaries"),
}

data class AppSettings(
    val calendarSettings: Map<Long, CalendarSettings>,
    val contactSettings: Map<ContactEventType, ContactSettings>,
) {
    val calendarConfigs: Map<Long, CalendarAlertConfig>
        get() =
            calendarSettings
                .filterValues { it.enabled }
                .mapValues { (_, settings) -> CalendarAlertConfig.createFromSettings(settings) }

    val contactConfigs: Map<ContactEventType, ContactAlertConfig>
        get() =
            contactSettings.mapValues { (_, settings) ->
                ContactAlertConfig.createFromSettings(settings)
            }
}

data class CalendarSettings(
    val enabled: Boolean,
    val notifyUnreminded: Boolean,
    val unremindedMinutes: Int,
    val dayOf: Boolean,
    val dayOfTime: Int,
    val dayBefore: Boolean,
    val dayBeforeTime: Int,
    val multiDayMode: MultiDayAlertMode,
) {
    fun updatePrefs(
        prefs: MutablePreferences,
        id: Long,
    ) {
        prefs[booleanPreferencesKey("calendar_${id}_enable")] = enabled
        prefs[booleanPreferencesKey("calendar_${id}_notify_unreminded")] = notifyUnreminded
        prefs[intPreferencesKey("calendar_${id}_unreminded_minutes")] = unremindedMinutes
        prefs[booleanPreferencesKey("calendar_${id}_day_of")] = dayOf
        prefs[intPreferencesKey("calendar_${id}_day_of_time")] = dayOfTime
        prefs[booleanPreferencesKey("calendar_${id}_day_before")] = dayBefore
        prefs[intPreferencesKey("calendar_${id}_day_before_time")] = dayBeforeTime
        prefs[stringPreferencesKey("calendar_${id}_multi_day_mode")] = multiDayMode.name
    }

    companion object {
        val DEFAULT =
            CalendarSettings(
                enabled = false,
                notifyUnreminded = false,
                unremindedMinutes = DEFAULT_UNREMINDED_MINUTES,
                dayOf = false,
                dayOfTime = DEFAULT_DAY_OF_TIME,
                dayBefore = false,
                dayBeforeTime = DEFAULT_DAY_BEFORE_TIME,
                multiDayMode = MultiDayAlertMode.OFF,
            )

        fun createFromPrefs(
            prefs: Preferences,
            id: Long,
        ): CalendarSettings {
            val multiDayModeStr =
                prefs[stringPreferencesKey("calendar_${id}_multi_day_mode")]
                    ?: DEFAULT.multiDayMode.name
            val multiDayMode =
                try {
                    MultiDayAlertMode.valueOf(multiDayModeStr)
                } catch (e: IllegalArgumentException) {
                    DEFAULT.multiDayMode
                }

            return CalendarSettings(
                enabled = prefs[booleanPreferencesKey("calendar_${id}_enable")] ?: DEFAULT.enabled,
                notifyUnreminded =
                    prefs[booleanPreferencesKey("calendar_${id}_notify_unreminded")]
                        ?: DEFAULT.notifyUnreminded,
                unremindedMinutes =
                    prefs[intPreferencesKey("calendar_${id}_unreminded_minutes")]
                        ?: DEFAULT.unremindedMinutes,
                dayOf = prefs[booleanPreferencesKey("calendar_${id}_day_of")] ?: DEFAULT.dayOf,
                dayOfTime =
                    prefs[intPreferencesKey("calendar_${id}_day_of_time")] ?: DEFAULT.dayOfTime,
                dayBefore =
                    prefs[booleanPreferencesKey("calendar_${id}_day_before")] ?: DEFAULT.dayBefore,
                dayBeforeTime =
                    prefs[intPreferencesKey("calendar_${id}_day_before_time")]
                        ?: DEFAULT.dayBeforeTime,
                multiDayMode = multiDayMode,
            )
        }
    }
}

data class ContactSettings(
    val timelinePins: Boolean,
    val dayOf: Boolean,
    val dayOfTime: Int,
    val dayBefore: Boolean,
    val dayBeforeTime: Int,
    val color: Int,
) {
    fun updatePrefs(
        prefs: MutablePreferences,
        type: ContactEventType,
    ) {
        val id = type.name
        prefs[booleanPreferencesKey("contacts_${id}_timeline_pins")] = timelinePins
        prefs[booleanPreferencesKey("contacts_${id}_day_of")] = dayOf
        prefs[intPreferencesKey("contacts_${id}_day_of_time")] = dayOfTime
        prefs[booleanPreferencesKey("contacts_${id}_day_before")] = dayBefore
        prefs[intPreferencesKey("contacts_${id}_day_before_time")] = dayBeforeTime
        prefs[intPreferencesKey("contacts_${id}_color")] = color
    }

    companion object {
        val DEFAULT =
            ContactSettings(
                timelinePins = false,
                dayOf = false,
                dayOfTime = DEFAULT_DAY_OF_TIME,
                dayBefore = false,
                dayBeforeTime = DEFAULT_DAY_BEFORE_TIME,
                color = SettingsRepository.PEBBLE_COLORS[3].toInt(), // Magenta
            )

        fun createFromPrefs(
            prefs: Preferences,
            type: ContactEventType,
        ): ContactSettings {
            val id = type.name
            return ContactSettings(
                timelinePins =
                    prefs[booleanPreferencesKey("contacts_${id}_timeline_pins")]
                        ?: DEFAULT.timelinePins,
                dayOf = prefs[booleanPreferencesKey("contacts_${id}_day_of")] ?: DEFAULT.dayOf,
                dayOfTime =
                    prefs[intPreferencesKey("contacts_${id}_day_of_time")] ?: DEFAULT.dayOfTime,
                dayBefore =
                    prefs[booleanPreferencesKey("contacts_${id}_day_before")] ?: DEFAULT.dayBefore,
                dayBeforeTime =
                    prefs[intPreferencesKey("contacts_${id}_day_before_time")]
                        ?: DEFAULT.dayBeforeTime,
                color = prefs[intPreferencesKey("contacts_${id}_color")] ?: DEFAULT.color,
            )
        }
    }
}

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
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
    }

    val appSettingsFlow: Flow<AppSettings> =
        dataStore.data.map { prefs ->
            val calendarSettings = mutableMapOf<Long, CalendarSettings>()

            prefs.asMap().forEach { (key, value) ->
                val match = CALENDAR_ENABLE_REGEX.matchEntire(key.name)
                if (match != null) {
                    val id = match.groupValues[1].toLong()
                    val enabled = value == true

                    calendarSettings[id] = CalendarSettings.createFromPrefs(prefs, id)
                }
            }

            val contactSettings =
                mapOf(
                    ContactEventType.BIRTHDAY to
                        ContactSettings.createFromPrefs(prefs, ContactEventType.BIRTHDAY),
                    ContactEventType.ANNIVERSARY to
                        ContactSettings.createFromPrefs(prefs, ContactEventType.ANNIVERSARY),
                )

            AppSettings(
                calendarSettings = calendarSettings,
                contactSettings = contactSettings,
            )
        }

    fun getCalendarSettingsFlow(id: Long): Flow<CalendarSettings> =
        dataStore.data.map { prefs -> CalendarSettings.createFromPrefs(prefs, id) }

    suspend fun updateCalendarSettings(
        id: Long,
        transform: (CalendarSettings) -> CalendarSettings,
    ) {
        dataStore.edit { prefs ->
            val current = CalendarSettings.createFromPrefs(prefs, id)
            transform(current).updatePrefs(prefs, id)
        }
    }

    fun getContactSettingsFlow(type: ContactEventType): Flow<ContactSettings> =
        dataStore.data.map { prefs -> ContactSettings.createFromPrefs(prefs, type) }

    suspend fun updateContactSettings(
        type: ContactEventType,
        transform: (ContactSettings) -> ContactSettings,
    ) {
        dataStore.edit { prefs ->
            val current = ContactSettings.createFromPrefs(prefs, type)
            transform(current).updatePrefs(prefs, type)
        }
    }
}
