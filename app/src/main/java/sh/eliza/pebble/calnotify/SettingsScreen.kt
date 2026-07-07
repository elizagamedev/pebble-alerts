package sh.eliza.pebble.calnotify

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import sh.eliza.pebble.calnotify.PebbleColor.Companion.PEBBLE_COLORS
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

fun formatDuration(
    context: Context,
    duration: Duration?,
): String {
    if (duration == null) return context.getString(R.string.duration_off)
    val minutes = duration.inWholeMinutes.toInt()
    if (minutes == 0) return context.getString(R.string.duration_when_event_starts)
    val fmt = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE)
    return if (minutes < 60) {
        fmt.format(Measure(minutes, MeasureUnit.MINUTE))
    } else if (minutes % 60 == 0) {
        fmt.format(Measure(minutes / 60, MeasureUnit.HOUR))
    } else {
        fmt.formatMeasures(
            Measure(minutes / 60, MeasureUnit.HOUR),
            Measure(minutes % 60, MeasureUnit.MINUTE),
        )
    }
}

fun formatTime(
    context: Context,
    time: LocalTime,
): String {
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
    return time.format(DateTimeFormatter.ofPattern(pattern))
}

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
)

fun getDeviceCalendars(context: Context): List<CalendarInfo> {
    if (!context.hasPermission(Manifest.permission.READ_CALENDAR)) {
        return emptyList()
    }

    val calendars = mutableListOf<CalendarInfo>()
    val uri = CalendarContract.Calendars.CONTENT_URI
    val projection =
        arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )

    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
        val nameIndex =
            cursor.getColumnIndexOrThrow(
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )
        val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)

        while (cursor.moveToNext()) {
            calendars.add(
                CalendarInfo(
                    id = cursor.getLong(idIndex),
                    name = cursor.getString(nameIndex),
                    accountName = cursor.getString(accountIndex),
                ),
            )
        }
    }
    return calendars
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onSyncRequest: () -> Unit,
) {
    val initialSettings = remember(repository) { repository.appSettingsFlow.value }
    val appSettings by repository.appSettingsFlow.collectAsState(initial = initialSettings)
    val currentSettings = appSettings ?: return

    val context = LocalContext.current
    val openPermissionSettings = {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        context.startActivity(intent)
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing),
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 5 },
                animationSpec = tween(300, easing = FastOutSlowInEasing),
            ) +
                fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                scaleOut(
                    targetScale = 0.95f,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 5 },
                animationSpec = tween(300, easing = FastOutSlowInEasing),
            ) +
                fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.95f,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing),
            )
        },
    ) {
        composable("home") {
            HomeScreen(
                settings = currentSettings,
                repository = repository,
                openPermissionSettings = openPermissionSettings,
                onNavigate = { navController.navigate(it) },
                onSyncRequest = onSyncRequest,
            )
        }
        composable(
            "calendar/{id}?name={name}&accountName={accountName}",
            arguments =
                listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("name") { type = NavType.StringType },
                    navArgument("accountName") { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            val name = backStackEntry.arguments?.getString("name") ?: ""
            val accountName = backStackEntry.arguments?.getString("accountName") ?: ""
            CalendarDetailScreen(
                id = id,
                name = name,
                accountName = accountName,
                settings = currentSettings.calendarSettings[id] ?: CalendarSettings.DEFAULT,
                repository = repository,
                onNavigateUp = { navController.navigateUp() },
            )
        }
        composable(
            "contacts/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id") ?: ""
            val eventType =
                runCatching { ContactEventType.valueOf(id) }.getOrDefault(ContactEventType.BIRTHDAY)

            ContactSettingsScreen(
                title = stringResource(eventType.labelResId),
                eventType = eventType,
                settings = currentSettings.contactSettings[eventType] ?: ContactSettings.DEFAULT,
                repository = repository,
                onNavigateUp = { navController.navigateUp() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    openPermissionSettings: () -> Unit,
    onNavigate: (String) -> Unit,
    onSyncRequest: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCalendarPermission by remember {
        mutableStateOf(context.hasPermission(Manifest.permission.READ_CALENDAR))
    }
    var hasContactsPermission by remember {
        mutableStateOf(context.hasPermission(Manifest.permission.READ_CONTACTS))
    }
    var calendars by remember { mutableStateOf(getDeviceCalendars(context)) }

    val snoozeDuration = settings.generalSettings.snoozeDuration
    var showSnoozeDurationDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasCalendarPermission = context.hasPermission(Manifest.permission.READ_CALENDAR)
                    hasContactsPermission = context.hasPermission(Manifest.permission.READ_CONTACTS)
                    calendars = getDeviceCalendars(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(id = R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            SettingsGroup {
                val lastSyncedStr =
                    if (settings.generalSettings.lastSynced != null) {
                        val date = java.util.Date(settings.generalSettings.lastSynced)
                        val format =
                            android.text.format.DateFormat
                                .getMediumDateFormat(context)
                        val timeFormat =
                            android.text.format.DateFormat
                                .getTimeFormat(context)
                        stringResource(
                            R.string.sync_time,
                            format.format(date),
                            timeFormat.format(date),
                        )
                    } else {
                        stringResource(R.string.sync_never)
                    }
                DependentValuePreference(
                    title = stringResource(R.string.pref_sync_now),
                    subtitle = lastSyncedStr,
                    enabled = true,
                    onClick = onSyncRequest,
                )
            }

            PreferenceCategory(title = stringResource(R.string.category_general))
            SettingsGroup {
                DependentValuePreference(
                    title = stringResource(R.string.pref_snooze_duration),
                    subtitle = formatDuration(context, snoozeDuration),
                    enabled = true,
                    onClick = { showSnoozeDurationDialog = true },
                )
            }

            if (!hasCalendarPermission || !hasContactsPermission) {
                PreferenceCategory(title = stringResource(R.string.category_permissions))
                SettingsGroup {
                    if (!hasCalendarPermission) {
                        PermissionSwitchPreference(
                            title = stringResource(R.string.permission_calendar),
                            permission = Manifest.permission.READ_CALENDAR,
                            openPermissionSettings = openPermissionSettings,
                        )
                    }
                    if (!hasCalendarPermission && !hasContactsPermission) {
                        SettingsGroupDivider()
                    }
                    if (!hasContactsPermission) {
                        PermissionSwitchPreference(
                            title = stringResource(R.string.permission_contacts),
                            permission = Manifest.permission.READ_CONTACTS,
                            openPermissionSettings = openPermissionSettings,
                        )
                    }
                }
            }

            PreferenceCategory(title = stringResource(R.string.category_calendars))
            if (!hasCalendarPermission) {
                SettingsGroup {
                    PermissionPlaceholder(
                        stringResource(R.string.permission_calendar_placeholder),
                    )
                }
            } else if (calendars.isEmpty()) {
                SettingsGroup { PermissionPlaceholder(stringResource(R.string.no_calendars_found)) }
            } else {
                SettingsGroup {
                    calendars.forEachIndexed { index, calendar ->
                        val calendarSettings =
                            settings.calendarSettings[calendar.id] ?: CalendarSettings.DEFAULT
                        CalendarListItem(
                            name = calendar.name,
                            accountName = calendar.accountName,
                            enabled = calendarSettings.enabled,
                            onToggle = { checked ->
                                coroutineScope.launch {
                                    repository.updateCalendarSettings(
                                        calendar.id,
                                    ) {
                                        it.copy(enabled = checked)
                                    }
                                }
                            },
                            onClick = {
                                onNavigate(
                                    "calendar/${calendar.id}" +
                                        "?name=${URLEncoder.encode(
                                            calendar.name,
                                            StandardCharsets.UTF_8.name(),
                                        ).replace("+", "%20")}" +
                                        "&accountName=${URLEncoder.encode(
                                            calendar.accountName,
                                            StandardCharsets.UTF_8.name(),
                                        ).replace("+", "%20")}",
                                )
                            },
                        )
                        if (index < calendars.size - 1) SettingsGroupDivider()
                    }
                }
            }

            PreferenceCategory(title = stringResource(R.string.category_events))
            if (!hasContactsPermission) {
                SettingsGroup {
                    PermissionPlaceholder(
                        stringResource(R.string.permission_contacts_placeholder),
                    )
                }
            } else {
                SettingsGroup {
                    NavigableListItem(
                        title = stringResource(ContactEventType.BIRTHDAY.labelResId),
                        onClick = { onNavigate("contacts/${ContactEventType.BIRTHDAY.name}") },
                    )
                    SettingsGroupDivider()
                    NavigableListItem(
                        title = stringResource(ContactEventType.ANNIVERSARY.labelResId),
                        onClick = { onNavigate("contacts/${ContactEventType.ANNIVERSARY.name}") },
                    )
                }
            }
        }

        if (showSnoozeDurationDialog) {
            RadioGroupDialog(
                title = stringResource(R.string.pref_snooze_duration),
                options =
                    listOf(
                        1.minutes,
                        2.minutes,
                        3.minutes,
                        5.minutes,
                        10.minutes,
                        15.minutes,
                        30.minutes,
                    ),
                selectedOption = snoozeDuration,
                onOptionSelected = { duration ->
                    coroutineScope.launch {
                        repository.updateGeneralSettings { it.copy(snoozeDuration = duration) }
                    }
                },
                onDismiss = { showSnoozeDurationDialog = false },
                optionLabel = { formatDuration(context, it) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDetailScreen(
    id: Long,
    name: String,
    accountName: String,
    settings: CalendarSettings,
    repository: SettingsRepository,
    onNavigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            CalendarSettingsGroup(
                id = id,
                enabled = true,
                settings = settings,
                repository = repository,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSettingsScreen(
    title: String,
    eventType: ContactEventType,
    settings: ContactSettings,
    repository: SettingsRepository,
    onNavigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
        ) {
            ContactsSettingsGroup(
                eventType = eventType,
                settings = settings,
                repository = repository,
            )
        }
    }
}

@Composable
fun ContactsSettingsGroup(
    eventType: ContactEventType,
    settings: ContactSettings,
    repository: SettingsRepository,
) {
    val scope = rememberCoroutineScope()

    fun updateSettings(transform: (ContactSettings) -> ContactSettings) {
        scope.launch { repository.updateContactSettings(eventType, transform) }
    }

    PreferenceCategory(title = stringResource(R.string.category_general))
    SettingsGroup {
        SwitchPreference(
            title = stringResource(R.string.pref_add_timeline_pins),
            checked = settings.timelinePins,
            onCheckedChange = { checked -> updateSettings { it.copy(timelinePins = checked) } },
        )
        SettingsGroupDivider()
        ColorPreference(
            title = stringResource(R.string.pref_accent_color),
            color = settings.color,
            onColorSelected = { color -> updateSettings { it.copy(color = color) } },
        )
    }

    DayAlertSettingsGroup(
        title = stringResource(R.string.category_day_of_alerts),
        switchTitle = stringResource(R.string.pref_day_of_alerts),
        checked = settings.dayOf,
        onCheckedChange = { checked -> updateSettings { it.copy(dayOf = checked) } },
        time = settings.dayOfTime,
        onTimeSelected = { time -> updateSettings { it.copy(dayOfTime = time) } },
        isGroupEnabled = true,
    )

    DayAlertSettingsGroup(
        title = stringResource(R.string.category_day_before_alerts),
        switchTitle = stringResource(R.string.pref_day_before_alerts),
        checked = settings.dayBefore,
        onCheckedChange = { checked -> updateSettings { it.copy(dayBefore = checked) } },
        time = settings.dayBeforeTime,
        onTimeSelected = { time -> updateSettings { it.copy(dayBeforeTime = time) } },
        isGroupEnabled = true,
    )
}

@Composable
fun DayAlertSettingsGroup(
    title: String,
    switchTitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    time: LocalTime,
    onTimeSelected: (LocalTime) -> Unit,
    isGroupEnabled: Boolean,
) {
    PreferenceCategory(title = title)
    SettingsGroup {
        SwitchPreference(
            title = switchTitle,
            checked = checked,
            enabled = isGroupEnabled,
            onCheckedChange = onCheckedChange,
        )
        SettingsGroupDivider()
        var showTimeDialog by remember { mutableStateOf(false) }
        val context = LocalContext.current
        DependentValuePreference(
            title = stringResource(R.string.pref_alert_time),
            subtitle = formatTime(context, time),
            enabled = isGroupEnabled && checked,
            onClick = { showTimeDialog = true },
        )
        if (showTimeDialog) {
            TimePickerDialog(
                initialTime = time,
                onDismiss = { showTimeDialog = false },
                onTimeSelected = { newTime ->
                    onTimeSelected(newTime)
                    showTimeDialog = false
                },
            )
        }
    }
}

@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        content()
    }
}

@Composable
fun SettingsGroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.background,
        thickness = 2.dp,
    )
}

@Composable
fun PreferenceCategory(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun SwitchPreference(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, color = if (enabled) Color.Unspecified else Color.Gray) },
        supportingContent =
            subtitle?.let { { Text(it, color = if (enabled) Color.Unspecified else Color.Gray) } },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
        modifier =
            Modifier.defaultMinSize(minHeight = 72.dp).clickable(enabled = enabled) {
                onCheckedChange(!checked)
            },
    )
}

@Composable
fun DependentValuePreference(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        },
        modifier =
            Modifier
                .defaultMinSize(minHeight = 72.dp)
                .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
fun <T> RadioGroupDialog(
    title: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    optionLabel: (T) -> String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .selectable(
                                selected = (option == selectedOption),
                                onClick = {
                                    onOptionSelected(option)
                                    onDismiss()
                                },
                                role = Role.RadioButton,
                            ).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (option == selectedOption),
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = optionLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onTimeSelected: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    val timePickerState =
        rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour =
                android.text.format.DateFormat
                    .is24HourFormat(context),
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_time)) },
        text = { TimePicker(state = timePickerState) },
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(LocalTime.of(timePickerState.hour, timePickerState.minute))
                },
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun CalendarSettingsGroup(
    id: Long,
    enabled: Boolean,
    settings: CalendarSettings,
    repository: SettingsRepository,
) {
    val scope = rememberCoroutineScope()

    fun updateSettings(transform: (CalendarSettings) -> CalendarSettings) {
        scope.launch { repository.updateCalendarSettings(id, transform) }
    }

    val isGroupEnabled = enabled && settings.enabled

    val context = LocalContext.current

    SettingsGroup {
        SwitchPreference(
            title = stringResource(R.string.pref_enable_alerts),
            checked = if (enabled) settings.enabled else false,
            enabled = enabled,
            onCheckedChange = { checked -> updateSettings { it.copy(enabled = checked) } },
        )
    }

    PreferenceCategory(title = stringResource(R.string.category_timed_events))
    SettingsGroup {
        var showDurationDialog by remember { mutableStateOf(false) }
        DependentValuePreference(
            title = stringResource(R.string.pref_default_reminder_time),
            subtitle = formatDuration(context, settings.unremindedOffset),
            enabled = isGroupEnabled,
            onClick = { showDurationDialog = true },
        )
        if (showDurationDialog) {
            RadioGroupDialog(
                title = stringResource(R.string.pref_default_reminder_time),
                options =
                    listOf(
                        null,
                        0.minutes,
                        5.minutes,
                        10.minutes,
                        15.minutes,
                        30.minutes,
                        1.hours,
                        3.hours,
                        6.hours,
                        12.hours,
                        24.hours,
                    ),
                selectedOption = settings.unremindedOffset,
                onOptionSelected = { duration ->
                    updateSettings { it.copy(unremindedOffset = duration) }
                },
                onDismiss = { showDurationDialog = false },
                optionLabel = { formatDuration(context, it) },
            )
        }
    }

    DayAlertSettingsGroup(
        title = stringResource(R.string.category_all_day_events_day_of),
        switchTitle = stringResource(R.string.pref_day_of_alerts),
        checked = settings.dayOf,
        onCheckedChange = { checked -> updateSettings { it.copy(dayOf = checked) } },
        time = settings.dayOfTime,
        onTimeSelected = { time -> updateSettings { it.copy(dayOfTime = time) } },
        isGroupEnabled = isGroupEnabled,
    )

    DayAlertSettingsGroup(
        title = stringResource(R.string.category_all_day_events_day_before),
        switchTitle = stringResource(R.string.pref_day_before_alerts),
        checked = settings.dayBefore,
        onCheckedChange = { checked -> updateSettings { it.copy(dayBefore = checked) } },
        time = settings.dayBeforeTime,
        onTimeSelected = { time -> updateSettings { it.copy(dayBeforeTime = time) } },
        isGroupEnabled = isGroupEnabled,
    )

    PreferenceCategory(title = stringResource(R.string.category_multi_day_events))
    SettingsGroup {
        var showMultiDayDialog by remember { mutableStateOf(false) }
        DependentValuePreference(
            title = stringResource(R.string.pref_alerts_for_multi_day_events),
            subtitle = stringResource(settings.multiDayMode.labelResId),
            enabled = isGroupEnabled,
            onClick = { showMultiDayDialog = true },
        )
        if (showMultiDayDialog) {
            RadioGroupDialog(
                title = stringResource(R.string.pref_alerts_for_multi_day_events),
                options = MultiDayAlertMode.values().toList(),
                selectedOption = settings.multiDayMode,
                onOptionSelected = { mode -> updateSettings { it.copy(multiDayMode = mode) } },
                onDismiss = { showMultiDayDialog = false },
                optionLabel = { context.getString(it.labelResId) },
            )
        }
    }
}

@Composable
fun PermissionSwitchPreference(
    title: String,
    permission: String,
    openPermissionSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(context.hasPermission(permission)) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasPermission = context.hasPermission(permission)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasPermission = isGranted
            if (!isGranted) {
                val activity = context as Activity
                if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                    openPermissionSettings()
                }
            }
        }

    SwitchPreference(
        title = title,
        checked = hasPermission,
        onCheckedChange = { checked ->
            if (checked) {
                launcher.launch(permission)
            } else {
                openPermissionSettings()
            }
        },
    )
}

@Composable
fun ColorPickerDialog(
    selectedColor: PebbleColor,
    onColorSelected: (PebbleColor) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pref_accent_color)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 44.dp),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(PEBBLE_COLORS.size) { index ->
                    val color = PEBBLE_COLORS[index]
                    Box(
                        modifier =
                            Modifier
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(color.toAndroidColorCorrected())
                                .border(
                                    width = if (color == selectedColor) 3.dp else 0.dp,
                                    color =
                                        if (color == selectedColor) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            Color.Transparent
                                        },
                                    shape = CircleShape,
                                ).clickable {
                                    onColorSelected(color)
                                    onDismiss()
                                },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
fun CalendarListItem(
    name: String,
    accountName: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .defaultMinSize(minHeight = 72.dp)
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        VerticalDivider(
            modifier = Modifier.padding(vertical = 8.dp).height(56.dp),
        )
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
fun NavigableListItem(
    title: String,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        modifier = Modifier.defaultMinSize(minHeight = 72.dp).clickable(onClick = onClick),
    )
}

@Composable
fun ColorPreference(
    title: String,
    color: PebbleColor,
    onColorSelected: (PebbleColor) -> Unit,
) {
    var showColorDialog by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        trailingContent = {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(color.toAndroidColorCorrected()),
                )
            }
        },
        modifier = Modifier.defaultMinSize(minHeight = 72.dp).clickable { showColorDialog = true },
    )
    if (showColorDialog) {
        ColorPickerDialog(
            selectedColor = color,
            onColorSelected = { selected ->
                onColorSelected(selected)
                showColorDialog = false
            },
            onDismiss = { showColorDialog = false },
        )
    }
}

@Composable
fun PermissionPlaceholder(text: String) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(text, color = Color.Gray) },
        modifier = Modifier.defaultMinSize(minHeight = 72.dp),
    )
}
