package sh.eliza.pebble.calnotify

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val CALENDAR_ENABLE_REGEX = Regex("""^calendar_(\d+)_enable$""")

private val DEFAULT_DAY_OF_TIME = LocalTime.of(9, 0) // 9:00 AM
private val DEFAULT_DAY_BEFORE_TIME = LocalTime.of(18, 0) // 6:00 PM

private const val TAG = "SettingsRepository"

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private fun Preferences.readDuration(
    key: String,
    default: Duration?,
): Duration? {
    val rawVal = this[intPreferencesKey(key)]
    return if (rawVal == null) {
        default
    } else if (rawVal == -1) {
        null
    } else {
        rawVal.seconds
    }
}

private fun MutablePreferences.writeDuration(
    key: String,
    value: Duration?,
) {
    this[intPreferencesKey(key)] = value?.inWholeSeconds?.toInt() ?: -1
}

enum class MultiDayAlertMode(
    val labelResId: Int,
) {
    OFF(R.string.multi_day_mode_off),
    FIRST_DAY(R.string.multi_day_mode_first_day),
    EVERY_DAY(R.string.multi_day_mode_every_day),
}

enum class ContactEventType(
    val labelResId: Int,
) {
    BIRTHDAY(R.string.contact_event_type_birthday),
    ANNIVERSARY(R.string.contact_event_type_anniversary),
}

enum class VibePattern(
    @param:StringRes val labelResId: Int,
    val value: Int,
) {
    NONE(R.string.vibe_pattern_none, 0),
    SHORT(R.string.vibe_pattern_short, 1),
    LONG(R.string.vibe_pattern_long, 2),
    DOUBLE(R.string.vibe_pattern_double, 3),
}

data class GeneralSettings(
    val snoozeDuration: Duration,
    val vibePattern: VibePattern,
    val lastSynced: Instant?,
) {
    fun updatePrefs(prefs: MutablePreferences) {
        prefs.writeDuration("general_snooze_duration", snoozeDuration)
        prefs[stringPreferencesKey("general_vibe_pattern")] = vibePattern.name
        if (lastSynced != null) {
            prefs[longPreferencesKey("general_last_synced")] = lastSynced.toEpochMilli()
        } else {
            prefs.remove(longPreferencesKey("general_last_synced"))
        }
    }

    companion object {
        val DEFAULT =
            GeneralSettings(
                snoozeDuration = 10.minutes,
                vibePattern = VibePattern.LONG,
                lastSynced = null,
            )

        fun createFromPrefs(prefs: Preferences): GeneralSettings =
            GeneralSettings(
                snoozeDuration =
                    prefs.readDuration("general_snooze_duration", null) ?: DEFAULT.snoozeDuration,
                vibePattern =
                    prefs[stringPreferencesKey("general_vibe_pattern")]?.let {
                        try {
                            VibePattern.valueOf(it)
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    } ?: DEFAULT.vibePattern,
                lastSynced =
                    prefs[
                        longPreferencesKey(
                            "general_last_synced",
                        ),
                    ]?.let { Instant.ofEpochMilli(it) },
            )
    }
}

data class AppSettings(
    val generalSettings: GeneralSettings,
    val calendarSettings: Map<Long, CalendarSettings>,
    val contactSettings: Map<ContactEventType, ContactSettings>,
)

data class CalendarSettings(
    val enabled: Boolean,
    val unremindedOffset: Duration?,
    val dayOf: Boolean,
    val dayOfTime: LocalTime,
    val dayBefore: Boolean,
    val dayBeforeTime: LocalTime,
    val multiDayMode: MultiDayAlertMode,
) {
    fun updatePrefs(
        prefs: MutablePreferences,
        id: Long,
    ) {
        prefs[booleanPreferencesKey("calendar_${id}_enable")] = enabled
        prefs.writeDuration("calendar_${id}_unreminded_offset", unremindedOffset)
        prefs[booleanPreferencesKey("calendar_${id}_day_of")] = dayOf
        prefs[intPreferencesKey("calendar_${id}_day_of_time")] = dayOfTime.toSecondOfDay()
        prefs[booleanPreferencesKey("calendar_${id}_day_before")] = dayBefore
        prefs[intPreferencesKey("calendar_${id}_day_before_time")] = dayBeforeTime.toSecondOfDay()
        prefs[stringPreferencesKey("calendar_${id}_multi_day_mode")] = multiDayMode.name
    }

    companion object {
        val DEFAULT =
            CalendarSettings(
                enabled = false,
                unremindedOffset = null,
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

            val notifyUnreminded = prefs[booleanPreferencesKey("calendar_${id}_notify_unreminded")]
            val unremindedOffset =
                if (notifyUnreminded == false) {
                    null
                } else {
                    prefs.readDuration(
                        "calendar_${id}_unreminded_offset",
                        if (notifyUnreminded == true) 10.minutes else DEFAULT.unremindedOffset,
                    )
                }

            return CalendarSettings(
                enabled = prefs[booleanPreferencesKey("calendar_${id}_enable")] ?: DEFAULT.enabled,
                unremindedOffset = unremindedOffset,
                dayOf = prefs[booleanPreferencesKey("calendar_${id}_day_of")] ?: DEFAULT.dayOf,
                dayOfTime =
                    prefs[intPreferencesKey("calendar_${id}_day_of_time")]?.let {
                        LocalTime.ofSecondOfDay(it.toLong())
                    } ?: DEFAULT.dayOfTime,
                dayBefore =
                    prefs[booleanPreferencesKey("calendar_${id}_day_before")] ?: DEFAULT.dayBefore,
                dayBeforeTime =
                    prefs[intPreferencesKey("calendar_${id}_day_before_time")]?.let {
                        LocalTime.ofSecondOfDay(it.toLong())
                    } ?: DEFAULT.dayBeforeTime,
                multiDayMode = multiDayMode,
            )
        }
    }
}

data class ContactSettings(
    val timelinePins: Boolean,
    val dayOf: Boolean,
    val dayOfTime: LocalTime,
    val dayBefore: Boolean,
    val dayBeforeTime: LocalTime,
    val color: PebbleColor,
) {
    fun updatePrefs(
        prefs: MutablePreferences,
        type: ContactEventType,
    ) {
        val id = type.name
        prefs[booleanPreferencesKey("contacts_${id}_timeline_pins")] = timelinePins
        prefs[booleanPreferencesKey("contacts_${id}_day_of")] = dayOf
        prefs[intPreferencesKey("contacts_${id}_day_of_time")] = dayOfTime.toSecondOfDay()
        prefs[booleanPreferencesKey("contacts_${id}_day_before")] = dayBefore
        prefs[intPreferencesKey("contacts_${id}_day_before_time")] = dayBeforeTime.toSecondOfDay()
        prefs[intPreferencesKey("contacts_${id}_color")] = color.toInt()
    }

    companion object {
        val DEFAULT =
            ContactSettings(
                timelinePins = false,
                dayOf = false,
                dayOfTime = DEFAULT_DAY_OF_TIME,
                dayBefore = false,
                dayBeforeTime = DEFAULT_DAY_BEFORE_TIME,
                color = PebbleColor(243), // Magenta
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
                    prefs[intPreferencesKey("contacts_${id}_day_of_time")]?.let {
                        LocalTime.ofSecondOfDay(it.toLong())
                    } ?: DEFAULT.dayOfTime,
                dayBefore =
                    prefs[booleanPreferencesKey("contacts_${id}_day_before")] ?: DEFAULT.dayBefore,
                dayBeforeTime =
                    prefs[intPreferencesKey("contacts_${id}_day_before_time")]?.let {
                        LocalTime.ofSecondOfDay(it.toLong())
                    } ?: DEFAULT.dayBeforeTime,
                color =
                    prefs[intPreferencesKey("contacts_${id}_color")]?.let { PebbleColor(it) }
                        ?: DEFAULT.color,
            )
        }
    }
}

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val appSettingsFlow: StateFlow<AppSettings?> =
        dataStore.data
            .map<Preferences, AppSettings?> { prefs ->
                val calendarSettings =
                    prefs
                        .asMap()
                        .keys
                        .mapNotNull {
                            CALENDAR_ENABLE_REGEX
                                .matchEntire(
                                    it.name,
                                )?.groupValues
                                ?.get(1)
                                ?.toLong()
                        }.associateWith { id -> CalendarSettings.createFromPrefs(prefs, id) }

                val contactSettings =
                    ContactEventType.entries.associateWith { type ->
                        ContactSettings.createFromPrefs(prefs, type)
                    }

                val generalSettings = GeneralSettings.createFromPrefs(prefs)

                AppSettings(
                    generalSettings = generalSettings,
                    calendarSettings = calendarSettings,
                    contactSettings = contactSettings,
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null,
            )

    suspend fun updateGeneralSettings(transform: (GeneralSettings) -> GeneralSettings) {
        dataStore.edit { prefs ->
            val current = GeneralSettings.createFromPrefs(prefs)
            transform(current).updatePrefs(prefs)
        }
    }

    suspend fun updateCalendarSettings(
        id: Long,
        transform: (CalendarSettings) -> CalendarSettings,
    ) {
        dataStore.edit { prefs ->
            val current = CalendarSettings.createFromPrefs(prefs, id)
            transform(current).updatePrefs(prefs, id)
        }
    }

    suspend fun updateContactSettings(
        type: ContactEventType,
        transform: (ContactSettings) -> ContactSettings,
    ) {
        dataStore.edit { prefs ->
            val current = ContactSettings.createFromPrefs(prefs, type)
            transform(current).updatePrefs(prefs, type)
        }
    }

    val lastSentAlertFlow: Flow<Alert?> =
        dataStore.data
            .map { prefs ->
                prefs[stringPreferencesKey("last_sent_alert")]?.let {
                    try {
                        Json.decodeFromString<Alert>(it)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to deserialize last_sent_alert", e)
                        null
                    }
                }
            }

    suspend fun updateLastSentAlert(alert: Alert?) {
        dataStore.edit { prefs ->
            if (alert != null) {
                prefs[stringPreferencesKey("last_sent_alert")] = Json.encodeToString(alert)
            } else {
                prefs.remove(stringPreferencesKey("last_sent_alert"))
            }
        }
    }
}
