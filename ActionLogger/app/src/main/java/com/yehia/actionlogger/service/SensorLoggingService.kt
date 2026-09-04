package com.yehia.actionlogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yehia.actionlogger.MainActivity
import com.yehia.actionlogger.R
import com.yehia.actionlogger.data.SensorDescriptor
import com.yehia.actionlogger.data.SessionConfig
import com.yehia.actionlogger.data.SessionRepository
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SensorLoggingService : Service(), SensorEventListener {
    companion object {
        const val ACTION_START = "com.yehia.actionlogger.START"
        const val ACTION_STOP = "com.yehia.actionlogger.STOP"
        const val ACTION_MARK = "com.yehia.actionlogger.MARK"

        const val EXTRA_SESSION_NAME = "session_name"
        const val EXTRA_ACTION_LABEL = "action_label"
        const val EXTRA_SUBJECT_ID = "subject_id"
        const val EXTRA_WRIST = "wrist"
        const val EXTRA_RATE_HZ = "rate_hz"
        const val EXTRA_SAMPLING_MODE = "sampling_mode"
        const val EXTRA_AUTO_STOP_MIN = "auto_stop_min"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_MARKER = "marker"

        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "recording"
        private const val PREFS = "recording_state"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_STARTED_MS = "started_ms"
        private const val KEY_ACTION_LABEL = "action_label"
        private const val BATCH_LATENCY_US = 2_000_000
        private const val CRITICAL_BATTERY_PCT = 7

        @Volatile private var serviceAlive = false

        fun isRecording(context: Context): Boolean =
            serviceAlive && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

        fun activeSessionId(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSION_ID, null)

        fun activeActionLabel(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTION_LABEL, null)
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var repository: SessionRepository
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private var eventWriter: BufferedWriter? = null
    private var markerWriter: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: String? = null
    private var startElapsedNs = 0L
    private var startWallMs = 0L
    private var eventCount = 0L
    private val eventsBySensor = linkedMapOf<String, Long>()
    private var autoStopMinutes = 20
    private var lastFlushElapsedMs = 0L
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        repository = SessionRepository(this)
        recoverInterruptedSessionIfNeeded()
        sensorThread = HandlerThread("ActionLoggerSensors").apply { start() }
        sensorHandler = Handler(sensorThread.looper)
        createNotificationChannel()
        serviceAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> requestStop("user")
            ACTION_MARK -> addMarker(intent.getStringExtra(EXTRA_MARKER).orEmpty().ifBlank { "marker" })
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(intent: Intent) {
        if (sessionId != null || getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)) return

        val requestedRate = intent.getIntExtra(EXTRA_RATE_HZ, 25).coerceIn(15, 50)
        val config = SessionConfig(
            subjectId = sanitizeText(intent.getStringExtra(EXTRA_SUBJECT_ID), "subject_001", 64),
            sessionName = sanitizeText(intent.getStringExtra(EXTRA_SESSION_NAME), "session", 96),
            actionLabel = sanitizeText(intent.getStringExtra(EXTRA_ACTION_LABEL), "other", 64),
            wrist = sanitizeText(intent.getStringExtra(EXTRA_WRIST), "left", 8),
            sampleRateHz = requestedRate,
            samplingMode = sanitizeText(intent.getStringExtra(EXTRA_SAMPLING_MODE), "balanced", 24),
            autoStopMinutes = intent.getIntExtra(EXTRA_AUTO_STOP_MIN, 20).coerceIn(5, 120),
            notes = sanitizeText(intent.getStringExtra(EXTRA_NOTES), "", 500)
        )

        val sensors = selectedSensors()
        if (sensors.none { it.type == Sensor.TYPE_ACCELEROMETER } || sensors.none { it.type == Sensor.TYPE_GYROSCOPE }) {
            stopSelf()
            return
        }

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", java.util.Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        sessionId = "${timestamp}_${UUID.randomUUID().toString().take(8)}"
        startElapsedNs = SystemClock.elapsedRealtimeNanos()
        startWallMs = System.currentTimeMillis()
        autoStopMinutes = config.autoStopMinutes
        eventCount = 0L
        eventsBySensor.clear()
        stopping.set(false)

        val (batteryPct, charging) = batteryState()
        val dir = repository.writeInitialMetadata(
            id = sessionId!!,
            config = config,
            startedUtc = Instant.ofEpochMilli(startWallMs).toString(),
            startedWallMs = startWallMs,
            startedElapsedNs = startElapsedNs,
            batteryPct = batteryPct,
            charging = charging,
            sensorBatchLatencyMs = BATCH_LATENCY_US / 1000,
            sensors = sensors.map(::descriptor)
        )

        eventWriter = BufferedWriter(FileWriter(File(dir, "sensor_events.csv"), false), 64 * 1024).also {
            it.write("sequence,sensor_timestamp_ns,callback_elapsed_realtime_ns,wall_time_utc_ms,sensor_type,string_type,accuracy,v0,v1,v2,v3,v4\n")
        }
        markerWriter = BufferedWriter(FileWriter(File(dir, "markers.csv"), false), 8 * 1024).also {
            it.write("elapsed_realtime_ns,wall_time_utc_ms,marker,note\n")
            val nowNs = SystemClock.elapsedRealtimeNanos()
            it.write("$nowNs,${wallTimeFromElapsed(nowNs)},session_start,\"\"\n")
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_ACTION_LABEL, config.actionLabel)
            .putLong(KEY_STARTED_MS, startWallMs)
            .apply()

        val maxDurationMs = (config.autoStopMinutes + 1L) * 60_000L
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActionLogger:Recording")
            .apply { acquire(maxDurationMs) }

        startForeground(NOTIFICATION_ID, buildNotification(config.actionLabel))

        val samplingUs = 1_000_000 / config.sampleRateHz
        sensors.forEach { sensor ->
            sensorManager.registerListener(this, sensor, samplingUs, BATCH_LATENCY_US, sensorHandler)
        }

        lastFlushElapsedMs = SystemClock.elapsedRealtime()
        sensorHandler.postDelayed(::periodicCheck, 60_000L)
    }

    private fun selectedSensors(): List<Sensor> = buildList {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let(::add)
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let(::add)
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let(::add)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (sessionId == null) return
        val values = FloatArray(5) { Float.NaN }
        for (index in 0 until minOf(event.values.size, values.size)) values[index] = event.values[index]
        val stringType = event.sensor.stringType
        eventsBySensor[stringType] = (eventsBySensor[stringType] ?: 0L) + 1L

        val line = buildString(192) {
            append(eventCount)
            append(',').append(event.timestamp)
            append(',').append(SystemClock.elapsedRealtimeNanos())
            append(',').append(wallTimeFromElapsed(event.timestamp))
            append(',').append(event.sensor.type)
            append(',').append(csv(stringType))
            append(',').append(event.accuracy)
            values.forEach { append(',').append(if (it.isNaN()) "" else it.toString()) }
            append('\n')
        }
        eventWriter?.write(line)
        eventCount++

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastFlushElapsedMs >= 5_000L) {
            eventWriter?.flush()
            markerWriter?.flush()
            lastFlushElapsedMs = nowMs
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun addMarker(marker: String) {
        if (sessionId == null || stopping.get()) return
        sensorHandler.post {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            markerWriter?.write("$nowNs,${wallTimeFromElapsed(nowNs)},${csv(marker.take(64))},\"\"\n")
            markerWriter?.flush()
        }
    }

    private fun periodicCheck() {
        if (sessionId == null || stopping.get()) return
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startElapsedNs) / 1_000_000L
        val (batteryPct, charging) = batteryState()
        when {
            elapsedMs >= autoStopMinutes * 60_000L -> requestStop("auto_stop")
            batteryPct in 0..CRITICAL_BATTERY_PCT && !charging -> requestStop("critical_battery")
            else -> sensorHandler.postDelayed(::periodicCheck, 60_000L)
        }
    }

    private fun requestStop(reason: String) {
        if (!stopping.compareAndSet(false, true)) return
        sensorHandler.post {
            runCatching { sensorManager.flush(this) }
            // Give the hardware FIFO a short opportunity to deliver its final batch.
            sensorHandler.postDelayed({ finalizeStop(reason) }, 300L)
        }
    }

    private fun finalizeStop(reason: String) {
        sensorManager.unregisterListener(this)
        val nowNs = SystemClock.elapsedRealtimeNanos()
        runCatching { markerWriter?.write("$nowNs,${wallTimeFromElapsed(nowNs)},session_stop,${csv(reason)}\n") }
        runCatching { eventWriter?.flush() }
        runCatching { markerWriter?.flush() }
        runCatching { eventWriter?.close() }
        runCatching { markerWriter?.close() }
        eventWriter = null
        markerWriter = null

        val endWallMs = System.currentTimeMillis()
        val durationSeconds = ((nowNs - startElapsedNs) / 1_000_000_000L).coerceAtLeast(0L)
        val (batteryPct, charging) = batteryState()
        sessionId?.let { sid ->
            repository.finalizeMetadata(
                id = sid,
                endedUtc = Instant.ofEpochMilli(endWallMs).toString(),
                durationSeconds = durationSeconds,
                eventCount = eventCount,
                eventsBySensor = eventsBySensor.toMap(),
                stopReason = reason,
                batteryPct = batteryPct,
                charging = charging
            )
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        sessionId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceAlive = false
        if (sessionId != null) {
            stopping.set(true)
            sensorManager.unregisterListener(this)
            runCatching { eventWriter?.flush() }
            runCatching { markerWriter?.flush() }
            runCatching { eventWriter?.close() }
            runCatching { markerWriter?.close() }
            val (batteryPct, charging) = batteryState()
            repository.finalizeMetadata(
                id = sessionId!!,
                endedUtc = Instant.now().toString(),
                durationSeconds = ((SystemClock.elapsedRealtimeNanos() - startElapsedNs) / 1_000_000_000L).coerceAtLeast(0L),
                eventCount = eventCount,
                eventsBySensor = eventsBySensor.toMap(),
                stopReason = "service_destroyed",
                batteryPct = batteryPct,
                charging = charging
            )
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
            sessionId = null
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        sensorThread.quitSafely()
        super.onDestroy()
    }

    private fun recoverInterruptedSessionIfNeeded() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return
        val sid = prefs.getString(KEY_SESSION_ID, null) ?: run {
            prefs.edit().clear().apply()
            return
        }
        val startedMs = prefs.getLong(KEY_STARTED_MS, System.currentTimeMillis())
        val (batteryPct, charging) = batteryState()
        repository.finalizeMetadata(
            id = sid,
            endedUtc = Instant.now().toString(),
            durationSeconds = ((System.currentTimeMillis() - startedMs) / 1000L).coerceAtLeast(0L),
            eventCount = repository.eventCountForSession(sid),
            eventsBySensor = repository.eventCountsBySensorForSession(sid),
            stopReason = "process_interrupted",
            batteryPct = batteryPct,
            charging = charging
        )
        prefs.edit().clear().apply()
    }

    private fun wallTimeFromElapsed(elapsedNs: Long): Long =
        startWallMs + ((elapsedNs - startElapsedNs) / 1_000_000L)

    private fun batteryState(): Pair<Int, Boolean> {
        val manager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun descriptor(sensor: Sensor) = SensorDescriptor(
        type = sensor.type,
        stringType = sensor.stringType,
        name = sensor.name,
        vendor = sensor.vendor,
        version = sensor.version,
        resolution = sensor.resolution,
        maxRange = sensor.maximumRange,
        minDelayUs = sensor.minDelay,
        maxDelayUs = sensor.maxDelay,
        fifoMaxEventCount = sensor.fifoMaxEventCount,
        fifoReservedEventCount = sensor.fifoReservedEventCount,
        wakeUp = sensor.isWakeUpSensor,
        reportingMode = sensor.reportingMode
    )

    private fun sanitizeText(value: String?, fallback: String, maxLength: Int): String =
        value?.trim()?.take(maxLength).orEmpty().ifBlank { fallback }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(actionLabel: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SensorLoggingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("$actionLabel • sensors active")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }
}
