package com.yehia.actionlogger

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.yehia.actionlogger.data.SessionRepository
import com.yehia.actionlogger.data.SessionSummary
import com.yehia.actionlogger.service.SensorLoggingService
import kotlinx.coroutines.delay
import java.time.Instant

class MainActivity : ComponentActivity() {
    private var pendingStart: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.invoke()
        pendingStart = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ActionLoggerApp { startAction ->
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingStart = startAction
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startAction()
                    }
                }
            }
        }
    }
}

private enum class Screen { HOME, NEW, SESSIONS, SESSION_DETAIL, DELETE_CONFIRM }
private data class SamplingOption(val label: String, val mode: String, val hz: Int)

@Composable
private fun ActionLoggerApp(requestNotificationThenStart: ((() -> Unit)) -> Unit) {
    val context = LocalContext.current
    val repository = remember { SessionRepository(context) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var recording by remember { mutableStateOf(SensorLoggingService.isRecording(context)) }
    var sessions by remember { mutableStateOf(repository.listSessions()) }
    var selectedSession by remember { mutableStateOf<SessionSummary?>(null) }
    var lastExportPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            recording = SensorLoggingService.isRecording(context)
            if (!recording && screen == Screen.HOME) sessions = repository.listSessions()
            delay(1_000L)
        }
    }

    when {
        recording -> RecordingScreen(
            actionLabel = SensorLoggingService.activeActionLabel(context),
            sessionId = SensorLoggingService.activeSessionId(context),
            onMark = {
                context.startService(Intent(context, SensorLoggingService::class.java).apply {
                    action = SensorLoggingService.ACTION_MARK
                    putExtra(SensorLoggingService.EXTRA_MARKER, "manual_marker")
                })
            },
            onStop = {
                context.startService(Intent(context, SensorLoggingService::class.java).setAction(SensorLoggingService.ACTION_STOP))
            }
        )

        screen == Screen.HOME -> HomeScreen(
            sessionCount = sessions.size,
            lastExportPath = lastExportPath,
            onNew = { screen = Screen.NEW },
            onSessions = {
                sessions = repository.listSessions()
                screen = Screen.SESSIONS
            },
            onExport = {
                lastExportPath = repository.exportAll().absolutePath
            }
        )

        screen == Screen.NEW -> NewSessionScreen(
            knownActions = (listOf("smoking", "drinking", "eating", "face_touch", "phone_use", "idle", "walking") +
                sessions.map { it.actionLabel }).distinct(),
            onBack = { screen = Screen.HOME },
            onStart = { actionLabel, subjectId, wrist, sampling, autoStop, notes ->
                val startAction = {
                    val sessionName = "${repository.actionSlug(actionLabel)}_${Instant.now().toString().replace(':', '-')}"
                    val intent = Intent(context, SensorLoggingService::class.java).apply {
                        action = SensorLoggingService.ACTION_START
                        putExtra(SensorLoggingService.EXTRA_SESSION_NAME, sessionName)
                        putExtra(SensorLoggingService.EXTRA_ACTION_LABEL, actionLabel)
                        putExtra(SensorLoggingService.EXTRA_SUBJECT_ID, subjectId)
                        putExtra(SensorLoggingService.EXTRA_WRIST, wrist)
                        putExtra(SensorLoggingService.EXTRA_RATE_HZ, sampling.hz)
                        putExtra(SensorLoggingService.EXTRA_SAMPLING_MODE, sampling.mode)
                        putExtra(SensorLoggingService.EXTRA_AUTO_STOP_MIN, autoStop)
                        putExtra(SensorLoggingService.EXTRA_NOTES, notes)
                    }
                    ContextCompat.startForegroundService(context, intent)
                    recording = true
                }
                requestNotificationThenStart(startAction)
            }
        )

        screen == Screen.SESSIONS -> SessionsScreen(
            sessions = sessions,
            onBack = { screen = Screen.HOME },
            onOpen = {
                selectedSession = it
                screen = Screen.SESSION_DETAIL
            }
        )

        screen == Screen.SESSION_DETAIL -> SessionDetailScreen(
            session = selectedSession,
            onBack = { screen = Screen.SESSIONS },
            onDelete = { screen = Screen.DELETE_CONFIRM }
        )

        screen == Screen.DELETE_CONFIRM -> DeleteConfirmScreen(
            session = selectedSession,
            onCancel = { screen = Screen.SESSION_DETAIL },
            onDelete = {
                selectedSession?.let { repository.deleteSession(it.id) }
                sessions = repository.listSessions()
                selectedSession = null
                screen = Screen.SESSIONS
            }
        )
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EditableValue(value: String, onValueChange: (String) -> Unit, hint: String) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(64)) },
        singleLine = true,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
        modifier = Modifier.fillMaxWidth(0.88f).border(1.dp, MaterialTheme.colorScheme.outline).padding(9.dp),
        decorationBox = { inner ->
            if (value.isBlank()) Text(hint, style = MaterialTheme.typography.bodySmall)
            inner()
        }
    )
}

@Composable
private fun HomeScreen(
    sessionCount: Int,
    lastExportPath: String?,
    onNew: () -> Unit,
    onSessions: () -> Unit,
    onExport: () -> Unit
) = Page {
    Text("Action Logger", style = MaterialTheme.typography.titleMedium)
    Text("ML training recorder", style = MaterialTheme.typography.bodySmall)
    Button(onClick = onNew, modifier = Modifier.fillMaxWidth(0.86f)) { Text("Create session") }
    Button(onClick = onSessions, modifier = Modifier.fillMaxWidth(0.86f)) { Text("Sessions ($sessionCount)") }
    Button(onClick = onExport, modifier = Modifier.fillMaxWidth(0.86f), enabled = sessionCount > 0) { Text("Export dataset") }
    if (lastExportPath != null) {
        Text("Export created", style = MaterialTheme.typography.labelMedium)
        Text(lastExportPath, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NewSessionScreen(
    knownActions: List<String>,
    onBack: () -> Unit,
    onStart: (String, String, String, SamplingOption, Int, String) -> Unit
) {
    val actions = knownActions + "custom"
    val wrists = listOf("left", "right")
    val sampling = listOf(
        SamplingOption("Battery • 15 Hz", "battery", 15),
        SamplingOption("Balanced • 25 Hz", "balanced", 25),
        SamplingOption("High precision • 50 Hz", "high_precision", 50)
    )
    val stops = listOf(10, 20, 30, 60)
    var actionIndex by remember { mutableIntStateOf(0) }
    var customAction by remember { mutableStateOf("") }
    var subjectId by remember { mutableStateOf("subject_001") }
    var wristIndex by remember { mutableIntStateOf(0) }
    var samplingIndex by remember { mutableIntStateOf(1) }
    var stopIndex by remember { mutableIntStateOf(1) }
    var notes by remember { mutableStateOf("") }

    val actionLabel = if (actions[actionIndex] == "custom") customAction.trim() else actions[actionIndex]

    Page {
        Text("Create session", style = MaterialTheme.typography.titleMedium)

        Text("Action", style = MaterialTheme.typography.labelMedium)
        Button(onClick = { actionIndex = (actionIndex + 1) % actions.size }, modifier = Modifier.fillMaxWidth(0.88f)) {
            Text(if (actions[actionIndex] == "custom") "Custom action" else actions[actionIndex])
        }
        if (actions[actionIndex] == "custom") {
            EditableValue(customAction, { customAction = it }, "Action name")
        }

        Text("Subject", style = MaterialTheme.typography.labelMedium)
        EditableValue(subjectId, { subjectId = it }, "subject_001")

        Text("Watch wrist", style = MaterialTheme.typography.labelMedium)
        Button(onClick = { wristIndex = (wristIndex + 1) % wrists.size }, modifier = Modifier.fillMaxWidth(0.88f)) {
            Text(wrists[wristIndex])
        }

        Text("Sampling", style = MaterialTheme.typography.labelMedium)
        Button(onClick = { samplingIndex = (samplingIndex + 1) % sampling.size }, modifier = Modifier.fillMaxWidth(0.88f)) {
            Text(sampling[samplingIndex].label)
        }

        Text("Auto-stop", style = MaterialTheme.typography.labelMedium)
        Button(onClick = { stopIndex = (stopIndex + 1) % stops.size }, modifier = Modifier.fillMaxWidth(0.88f)) {
            Text("${stops[stopIndex]} min")
        }

        Text("Notes (optional)", style = MaterialTheme.typography.labelMedium)
        EditableValue(notes, { notes = it }, "e.g. sitting at table")

        Text("25 Hz is recommended for normal gesture datasets.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { onStart(actionLabel, subjectId.ifBlank { "subject_001" }, wrists[wristIndex], sampling[samplingIndex], stops[stopIndex], notes) },
            enabled = actionLabel.isNotBlank(),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) { Text("Start recording") }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth(0.88f)) { Text("Back") }
    }
}

@Composable
private fun RecordingScreen(actionLabel: String?, sessionId: String?, onMark: () -> Unit, onStop: () -> Unit) = Page {
    Text("Recording", style = MaterialTheme.typography.titleMedium)
    Text(actionLabel ?: "Action", style = MaterialTheme.typography.titleSmall)
    Text(sessionId ?: "Active session", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    Text("Accelerometer + gyroscope + rotation", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    Button(onClick = onMark, modifier = Modifier.fillMaxWidth(0.84f)) { Text("Add marker") }
    Button(onClick = onStop, modifier = Modifier.fillMaxWidth(0.84f)) { Text("Stop") }
}

@Composable
private fun SessionsScreen(sessions: List<SessionSummary>, onBack: () -> Unit, onOpen: (SessionSummary) -> Unit) = Page {
    Text("Sessions", style = MaterialTheme.typography.titleMedium)
    if (sessions.isEmpty()) Text("No sessions")
    sessions.forEach { session ->
        Button(onClick = { onOpen(session) }, modifier = Modifier.fillMaxWidth(0.92f)) {
            Column {
                Text(session.actionLabel)
                Text("${session.durationSeconds ?: 0}s • ${session.eventCount} events", style = MaterialTheme.typography.bodySmall)
                Text("${session.samplingMode} • ${session.sampleRateHz} Hz", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth(0.84f)) { Text("Back") }
}

@Composable
private fun SessionDetailScreen(session: SessionSummary?, onBack: () -> Unit, onDelete: () -> Unit) = Page {
    if (session == null) {
        Text("Session unavailable")
        Button(onClick = onBack) { Text("Back") }
        return@Page
    }
    Text(session.actionLabel, style = MaterialTheme.typography.titleMedium)
    Text(session.name, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    Text("Subject: ${session.subjectId}")
    Text("Wrist: ${session.wrist}")
    Text("Rate: ${session.sampleRateHz} Hz")
    Text("Duration: ${session.durationSeconds ?: 0} s")
    Text("Events: ${session.eventCount}")
    Text("Stop: ${session.stopReason ?: "unfinished"}")
    Text("Folder: ${session.actionSlug}", style = MaterialTheme.typography.bodySmall)
    Button(onClick = onDelete, modifier = Modifier.fillMaxWidth(0.84f)) { Text("Delete") }
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth(0.84f)) { Text("Back") }
}

@Composable
private fun DeleteConfirmScreen(session: SessionSummary?, onCancel: () -> Unit, onDelete: () -> Unit) = Page {
    Text("Delete session?", style = MaterialTheme.typography.titleMedium)
    Text(session?.actionLabel ?: "Session", textAlign = TextAlign.Center)
    Text("This cannot be undone.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDelete) { Text("Delete") }
        Button(onClick = onCancel) { Text("Cancel") }
    }
}
