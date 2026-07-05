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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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

fun formatDuration(duration: Duration): String {
    val minutes = duration.inWholeMinutes.toInt()
    if (minutes == 0) return "At time of event"
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
fun SettingsScreen(repository: SettingsRepository) {
    val context = LocalContext.current
    val openSettings = {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        context.startActivity(intent)
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repository = repository,
                openSettings = openSettings,
                onNavigate = { navController.navigate(it) },
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
                title = eventType.label,
                eventType = eventType,
                repository = repository,
                onNavigateUp = { navController.navigateUp() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: SettingsRepository,
    openSettings: () -> Unit,
    onNavigate: (String) -> Unit,
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

    val generalSettingsFlow =
        remember(repository) {
            repository.appSettingsFlow
                .filterNotNull()
                .map { it.generalSettings }
                .distinctUntilChanged()
        }
    val generalSettings by generalSettingsFlow.collectAsState(initial = GeneralSettings.DEFAULT)
    val syncInterval = generalSettings.syncInterval
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
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
            PreferenceCategory(title = "General")
            SettingsGroup {
                DependentValuePreference(
                    title = "Sync interval",
                    subtitle = formatDuration(syncInterval),
                    enabled = true,
                    onClick = { showSyncIntervalDialog = true },
                )
            }

            PreferenceCategory(title = "Permissions")
            SettingsGroup {
                PermissionSwitchPreference(
                    title = "Calendar",
                    permission = Manifest.permission.READ_CALENDAR,
                    openSettings = openSettings,
                )
                PermissionSwitchPreference(
                    title = "Contacts",
                    permission = Manifest.permission.READ_CONTACTS,
                    openSettings = openSettings,
                )
            }

            PreferenceCategory(title = "Calendars")
            SettingsGroup {
                if (!hasCalendarPermission) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                "Please grant calendar permission to view or edit settings.",
                                color = Color.Gray,
                            )
                        },
                    )
                } else if (calendars.isEmpty()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("No calendars found.", color = Color.Gray) },
                    )
                } else {
                    calendars.forEach { calendar ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(calendar.name) },
                            supportingContent = { Text(calendar.accountName) },
                            modifier =
                                Modifier.clickable {
                                    onNavigate(
                                        "calendar/${calendar.id}?name=${URLEncoder.encode(
                                            calendar.name,
                                            StandardCharsets.UTF_8.name(),
                                        ).replace("+", "%20")}&accountName=${URLEncoder.encode(
                                            calendar.accountName,
                                            StandardCharsets.UTF_8.name(),
                                        ).replace("+", "%20")}",
                                    )
                                },
                        )
                    }
                }
            }

            PreferenceCategory(title = "Events")
            SettingsGroup {
                if (!hasContactsPermission) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                "Please grant contacts permission to view or edit settings.",
                                color = Color.Gray,
                            )
                        },
                    )
                } else {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(ContactEventType.BIRTHDAY.label) },
                        modifier =
                            Modifier.clickable {
                                onNavigate(
                                    "contacts/${ContactEventType.BIRTHDAY.name}",
                                )
                            },
                    )
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(ContactEventType.ANNIVERSARY.label) },
                        modifier =
                            Modifier.clickable {
                                onNavigate(
                                    "contacts/${ContactEventType.ANNIVERSARY.name}",
                                )
                            },
                    )
                }
            }
        }

        if (showSyncIntervalDialog) {
            DurationPickerDialog(
                title = "Sync interval",
                options =
                    listOf(
                        15.minutes,
                        30.minutes,
                        1.hours,
                        3.hours,
                        6.hours,
                        12.hours,
                        24.hours,
                    ),
                currentDuration = syncInterval,
                onDismiss = { showSyncIntervalDialog = false },
                onConfirm = { duration ->
                    coroutineScope.launch {
                        repository.updateGeneralSettings { it.copy(syncInterval = duration) }
                    }
                    showSyncIntervalDialog = false
                },
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
                            contentDescription = "Back",
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
            CalendarSettingsGroup(id = id, enabled = true, repository = repository)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSettingsScreen(
    title: String,
    eventType: ContactEventType,
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
                            contentDescription = "Back",
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
                repository = repository,
                eventType = eventType,
            )
        }
    }
}

@Composable
fun ContactsSettingsGroup(
    repository: SettingsRepository,
    eventType: ContactEventType,
) {
    val flow =
        remember(repository, eventType) {
            repository.appSettingsFlow
                .filterNotNull()
                .map { it.contactSettings[eventType] ?: ContactSettings.DEFAULT }
                .distinctUntilChanged()
        }
    val settings by flow.collectAsState(initial = ContactSettings.DEFAULT)

    val scope = rememberCoroutineScope()

    fun updateSettings(transform: (ContactSettings) -> ContactSettings) {
        scope.launch { repository.updateContactSettings(eventType, transform) }
    }

    PreferenceCategory(title = "General")
    SettingsGroup {
        SwitchPreference(
            title = "Add timeline pins",
            checked = settings.timelinePins,
            onCheckedChange = { checked -> updateSettings { it.copy(timelinePins = checked) } },
        )

        var showColorDialog by remember { mutableStateOf(false) }

        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("Accent color") },
            trailingContent = {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(settings.color.toAndroidColorCorrected()),
                    )
                }
            },
            modifier = Modifier.clickable { showColorDialog = true },
        )

        if (showColorDialog) {
            ColorPickerDialog(
                selectedColor = settings.color,
                onColorSelected = { colorArgb -> updateSettings { it.copy(color = colorArgb) } },
                onDismiss = { showColorDialog = false },
            )
        }
    }

    DayAlertSettingsGroup(
        title = "Day-of alerts",
        switchTitle = "Day-of alerts",
        checked = settings.dayOf,
        onCheckedChange = { checked -> updateSettings { it.copy(dayOf = checked) } },
        time = settings.dayOfTime,
        onTimeSelected = { time -> updateSettings { it.copy(dayOfTime = time) } },
        isGroupEnabled = true,
    )

    DayAlertSettingsGroup(
        title = "Day-before alerts",
        switchTitle = "Day-before alerts",
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

        var showTimeDialog by remember { mutableStateOf(false) }
        val context = LocalContext.current
        DependentValuePreference(
            title = "Alert time",
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
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
    }
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
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
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
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
fun DurationPickerDialog(
    title: String,
    options: List<Duration>,
    currentDuration: Duration,
    onDismiss: () -> Unit,
    onConfirm: (Duration) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { duration ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = (duration == currentDuration),
                                onClick = { onConfirm(duration) },
                                role = Role.RadioButton,
                            ).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (duration == currentDuration),
                            onClick = null,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        title = { Text("Select time") },
        text = { TimePicker(state = timePickerState) },
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(LocalTime.of(timePickerState.hour, timePickerState.minute))
                },
            ) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CalendarSettingsGroup(
    id: Long,
    enabled: Boolean,
    repository: SettingsRepository,
) {
    val flow =
        remember(repository, id) {
            repository.appSettingsFlow
                .filterNotNull()
                .map { it.calendarSettings[id] ?: CalendarSettings.DEFAULT }
                .distinctUntilChanged()
        }
    val settings by flow.collectAsState(initial = CalendarSettings.DEFAULT)

    val scope = rememberCoroutineScope()

    fun updateSettings(transform: (CalendarSettings) -> CalendarSettings) {
        scope.launch { repository.updateCalendarSettings(id, transform) }
    }

    val isGroupEnabled = enabled && settings.enabled

    SettingsGroup {
        SwitchPreference(
            title = "Enable alerts",
            checked = if (enabled) settings.enabled else false,
            enabled = enabled,
            onCheckedChange = { checked -> updateSettings { it.copy(enabled = checked) } },
        )
    }

    PreferenceCategory(title = "Events without reminders")
    SettingsGroup {
        SwitchPreference(
            title = "Alert for un-reminded events",
            checked = settings.notifyUnreminded,
            enabled = isGroupEnabled,
            onCheckedChange = { checked -> updateSettings { it.copy(notifyUnreminded = checked) } },
        )

        var showDurationDialog by remember { mutableStateOf(false) }
        DependentValuePreference(
            title = "Alert time before event",
            subtitle = formatDuration(settings.unremindedOffset),
            enabled = isGroupEnabled && settings.notifyUnreminded,
            onClick = { showDurationDialog = true },
        )

        if (showDurationDialog) {
            DurationPickerDialog(
                title = "Alert time before event",
                options =
                    listOf(
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
                currentDuration = settings.unremindedOffset,
                onDismiss = { showDurationDialog = false },
                onConfirm = { duration ->
                    updateSettings { it.copy(unremindedOffset = duration) }
                    showDurationDialog = false
                },
            )
        }
    }

    DayAlertSettingsGroup(
        title = "All-day events (day-of)",
        switchTitle = "Day-of alerts",
        checked = settings.dayOf,
        onCheckedChange = { checked -> updateSettings { it.copy(dayOf = checked) } },
        time = settings.dayOfTime,
        onTimeSelected = { time -> updateSettings { it.copy(dayOfTime = time) } },
        isGroupEnabled = isGroupEnabled,
    )

    DayAlertSettingsGroup(
        title = "All-day events (day-before)",
        switchTitle = "Day-before alerts",
        checked = settings.dayBefore,
        onCheckedChange = { checked -> updateSettings { it.copy(dayBefore = checked) } },
        time = settings.dayBeforeTime,
        onTimeSelected = { time -> updateSettings { it.copy(dayBeforeTime = time) } },
        isGroupEnabled = isGroupEnabled,
    )

    PreferenceCategory(title = "Multi-day events")
    SettingsGroup {
        var showMultiDayDialog by remember { mutableStateOf(false) }

        DependentValuePreference(
            title = "Alert frequency",
            subtitle = settings.multiDayMode.label,
            enabled = isGroupEnabled && (settings.dayOf || settings.dayBefore),
            onClick = { showMultiDayDialog = true },
        )

        if (showMultiDayDialog) {
            AlertDialog(
                onDismissRequest = { showMultiDayDialog = false },
                title = { Text("Multi-day alert mode") },
                text = {
                    Column {
                        MultiDayAlertMode.values().forEach { mode ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            updateSettings { it.copy(multiDayMode = mode) }
                                            showMultiDayDialog = false
                                        }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = mode == settings.multiDayMode,
                                    onClick = {
                                        updateSettings { it.copy(multiDayMode = mode) }
                                        showMultiDayDialog = false
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(mode.label)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMultiDayDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
fun PermissionSwitchPreference(
    title: String,
    permission: String,
    openSettings: () -> Unit,
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
                    openSettings()
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
                openSettings()
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
        title = { Text("Accent color") },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
