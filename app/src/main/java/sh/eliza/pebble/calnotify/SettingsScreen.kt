package sh.eliza.pebble.calnotify

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
)

fun getDeviceCalendars(context: Context): List<CalendarInfo> {
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
        PackageManager.PERMISSION_GRANTED
    ) {
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
            HomeScreen(openSettings = openSettings, onNavigate = { navController.navigate(it) })
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
    openSettings: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCalendarPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var calendars by remember { mutableStateOf(getDeviceCalendars(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasCalendarPermission =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CALENDAR,
                        ) == PackageManager.PERMISSION_GRANTED
                    hasContactsPermission =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CONTACTS,
                        ) == PackageManager.PERMISSION_GRANTED
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
    val flow = remember(repository, eventType) { repository.getContactSettingsFlow(eventType) }
    val settings by flow.collectAsState(initial = ContactSettings.DEFAULT)

    val timelinePins = settings.timelinePins
    val dayOf = settings.dayOf
    val dayOfTime = LocalTime.ofSecondOfDay(settings.dayOfTime.toLong())
    val dayBefore = settings.dayBefore
    val dayBeforeTime = LocalTime.ofSecondOfDay(settings.dayBeforeTime.toLong())
    val selectedColor = settings.color

    val scope = rememberCoroutineScope()

    fun updateSettings(transform: (ContactSettings) -> ContactSettings) {
        scope.launch { repository.updateContactSettings(eventType, transform) }
    }

    PreferenceCategory(title = "General")
    SettingsGroup {
        SwitchPreference(
            title = "Add timeline pins",
            checked = timelinePins,
            onCheckedChange = { checked -> updateSettings { it.copy(timelinePins = checked) } },
        )

        ColorPreference(
            title = "Color",
            selectedColor = selectedColor,
            onColorSelected = { colorArgb -> updateSettings { it.copy(color = colorArgb) } },
        )
    }

    PreferenceCategory(title = "Day-of Alerts")
    SettingsGroup {
        SwitchPreference(
            title = "Day-of alerts",
            checked = dayOf,
            onCheckedChange = { checked -> updateSettings { it.copy(dayOf = checked) } },
        )

        var showDayOfDialog by remember { mutableStateOf(false) }
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        DependentValuePreference(
            title = "Alert time",
            subtitle = dayOfTime.format(timeFormatter),
            enabled = dayOf,
            onClick = { showDayOfDialog = true },
        )

        if (showDayOfDialog) {
            TimePickerDialog(
                initialTime = dayOfTime,
                onDismiss = { showDayOfDialog = false },
                onTimeSelected = { time ->
                    updateSettings { it.copy(dayOfTime = time.toSecondOfDay()) }
                    showDayOfDialog = false
                },
            )
        }
    }

    PreferenceCategory(title = "Day-before Alerts")
    SettingsGroup {
        SwitchPreference(
            title = "Day-before alerts",
            checked = dayBefore,
            onCheckedChange = { checked -> updateSettings { it.copy(dayBefore = checked) } },
        )

        var showDayBeforeDialog by remember { mutableStateOf(false) }
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        DependentValuePreference(
            title = "Alert time",
            subtitle = dayBeforeTime.format(timeFormatter),
            enabled = dayBefore,
            onClick = { showDayBeforeDialog = true },
        )

        if (showDayBeforeDialog) {
            TimePickerDialog(
                initialTime = dayBeforeTime,
                onDismiss = { showDayBeforeDialog = false },
                onTimeSelected = { time ->
                    updateSettings { it.copy(dayBeforeTime = time.toSecondOfDay()) }
                    showDayBeforeDialog = false
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
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onDurationSelected: (Int) -> Unit,
) {
    val options = listOf(0, 5, 10, 15, 30, 60, 120)

    fun labelFor(mins: Int) = if (mins == 0) "Off" else "$mins minutes"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minutes before event") },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { mins ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = (mins == currentMinutes),
                                onClick = { onDurationSelected(mins) },
                                role = Role.RadioButton,
                            ).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (mins == currentMinutes),
                            onClick = null, // null recommended for accessibility with selectable
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = labelFor(mins),
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
    val flow = remember(repository, id) { repository.getCalendarSettingsFlow(id) }
    val settings by flow.collectAsState(initial = CalendarSettings.DEFAULT)

    val enableNotifications = settings.enabled
    val notifyUnreminded = settings.notifyUnreminded
    val unremindedMinutesBefore = settings.unremindedMinutes
    val dayOfAllDay = settings.dayOf
    val dayOfTime = LocalTime.ofSecondOfDay(settings.dayOfTime.toLong())
    val dayBeforeAllDay = settings.dayBefore
    val dayBeforeTime = LocalTime.ofSecondOfDay(settings.dayBeforeTime.toLong())
    val multiDayMode = settings.multiDayMode

    val scope = rememberCoroutineScope()

    fun updateSettings(transform: (CalendarSettings) -> CalendarSettings) {
        scope.launch { repository.updateCalendarSettings(id, transform) }
    }

    val isGroupEnabled = enabled && enableNotifications

    SettingsGroup {
        SwitchPreference(
            title = "Enable notifications",
            checked = if (enabled) enableNotifications else false,
            enabled = enabled,
            onCheckedChange = { checked -> updateSettings { it.copy(enabled = checked) } },
        )
    }

    PreferenceCategory(title = "Timed Events")
    SettingsGroup {
        SwitchPreference(
            title = "Notify for un-reminded events",
            checked = notifyUnreminded,
            enabled = isGroupEnabled,
            onCheckedChange = { checked -> updateSettings { it.copy(notifyUnreminded = checked) } },
        )

        var showDurationDialog by remember { mutableStateOf(false) }
        DependentValuePreference(
            title = "Fallback alert time",
            subtitle = "$unremindedMinutesBefore minutes before",
            enabled = isGroupEnabled && notifyUnreminded,
            onClick = { showDurationDialog = true },
        )

        if (showDurationDialog) {
            DurationPickerDialog(
                currentMinutes = unremindedMinutesBefore,
                onDismiss = { showDurationDialog = false },
                onDurationSelected = { minutes ->
                    updateSettings { it.copy(unremindedMinutes = minutes) }
                    showDurationDialog = false
                },
            )
        }
    }

    PreferenceCategory(title = "All-Day Events (Day-of)")
    SettingsGroup {
        SwitchPreference(
            title = "Day-of alerts",
            checked = dayOfAllDay,
            enabled = isGroupEnabled,
            onCheckedChange = { checked -> updateSettings { it.copy(dayOf = checked) } },
        )

        var showDayOfTimeDialog by remember { mutableStateOf(false) }
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        DependentValuePreference(
            title = "Alert time",
            subtitle = dayOfTime.format(timeFormatter),
            enabled = isGroupEnabled && dayOfAllDay,
            onClick = { showDayOfTimeDialog = true },
        )

        if (showDayOfTimeDialog) {
            TimePickerDialog(
                initialTime = dayOfTime,
                onDismiss = { showDayOfTimeDialog = false },
                onTimeSelected = { time ->
                    updateSettings { it.copy(dayOfTime = time.toSecondOfDay()) }
                    showDayOfTimeDialog = false
                },
            )
        }
    }

    PreferenceCategory(title = "All-Day Events (Day-before)")
    SettingsGroup {
        SwitchPreference(
            title = "Day-before alerts",
            checked = dayBeforeAllDay,
            enabled = isGroupEnabled,
            onCheckedChange = { checked -> updateSettings { it.copy(dayBefore = checked) } },
        )

        var showDayBeforeTimeDialog by remember { mutableStateOf(false) }
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        DependentValuePreference(
            title = "Alert time",
            subtitle = dayBeforeTime.format(timeFormatter),
            enabled = isGroupEnabled && dayBeforeAllDay,
            onClick = { showDayBeforeTimeDialog = true },
        )

        if (showDayBeforeTimeDialog) {
            TimePickerDialog(
                initialTime = dayBeforeTime,
                onDismiss = { showDayBeforeTimeDialog = false },
                onTimeSelected = { time ->
                    updateSettings { it.copy(dayBeforeTime = time.toSecondOfDay()) }
                    showDayBeforeTimeDialog = false
                },
            )
        }
    }

    PreferenceCategory(title = "Multi-Day Events")
    SettingsGroup {
        var showMultiDayDialog by remember { mutableStateOf(false) }

        DependentValuePreference(
            title = "Alert frequency",
            subtitle = multiDayMode.label,
            enabled = isGroupEnabled,
            onClick = { showMultiDayDialog = true },
        )

        if (showMultiDayDialog) {
            AlertDialog(
                onDismissRequest = { showMultiDayDialog = false },
                title = { Text("Multi-Day Alert Mode") },
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
                                    selected = multiDayMode == mode,
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

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasPermission =
                        ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
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
fun ColorPreference(
    title: String,
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
) {
    val pebbleColors = SettingsRepository.PEBBLE_COLORS

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(pebbleColors.size) { index ->
                val pColor = pebbleColors[index]
                val androidColor = pebbleToAndroidColor(pColor)
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(androidColor)
                            .border(
                                width = if (pColor.toInt() == selectedColor) 3.dp else 0.dp,
                                color =
                                    if (pColor.toInt() == selectedColor) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        Color.Transparent
                                    },
                                shape = CircleShape,
                            ).clickable { onColorSelected(pColor.toInt()) },
                )
            }
        }
    }
}
