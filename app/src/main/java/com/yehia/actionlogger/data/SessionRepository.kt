package com.yehia.actionlogger.data

import android.content.Context
import android.os.Build
import com.yehia.actionlogger.BuildConfig
import com.yehia.actionlogger.util.JsonUtil
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionRepository(private val context: Context) {
    private val sessionsRoot = File(context.filesDir, "ActionLogger/sessions").apply { mkdirs() }

    fun rootDir(): File = sessionsRoot

    fun actionSlug(label: String): String {
        val normalized = label.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return normalized.ifBlank { "other" }.take(64)
    }

    fun createSessionDir(actionLabel: String, id: String): File =
        File(File(sessionsRoot, actionSlug(actionLabel)), id).apply { mkdirs() }

    fun findSessionDir(id: String): File? = sessionsRoot
        .listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.map { File(it, id) }
        ?.firstOrNull { it.isDirectory }

    fun listSessions(): List<SessionSummary> = sessionsRoot
        .listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.flatMap { actionDir -> actionDir.listFiles()?.asSequence() ?: emptySequence() }
        ?.filter { it.isDirectory }
        ?.mapNotNull(::readSummary)
        ?.sortedByDescending { it.startedUtc }
        ?.toList()
        ?: emptyList()

    fun deleteSession(id: String): Boolean {
        val dir = findSessionDir(id) ?: return false
        val actionDir = dir.parentFile
        val deleted = dir.deleteRecursively()
        if (deleted && actionDir?.listFiles()?.isEmpty() == true) actionDir.delete()
        return deleted
    }

    fun eventCountForSession(id: String): Long =
        findSessionDir(id)?.let { countEvents(File(it, "sensor_events.csv")) } ?: 0L

    fun eventCountsBySensorForSession(id: String): Map<String, Long> {
        val file = findSessionDir(id)?.let { File(it, "sensor_events.csv") } ?: return emptyMap()
        if (!file.exists()) return emptyMap()
        val counts = linkedMapOf<String, Long>()
        file.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                // string_type is quoted and contains no commas for Android sensor type names.
                val parts = line.split(',')
                if (parts.size > 5) {
                    val type = parts[5].trim().removeSurrounding("\"")
                    if (type.isNotBlank()) counts[type] = (counts[type] ?: 0L) + 1L
                }
            }
        }
        return counts
    }

    fun writeInitialMetadata(
        id: String,
        config: SessionConfig,
        startedUtc: String,
        startedWallMs: Long,
        startedElapsedNs: Long,
        batteryPct: Int,
        charging: Boolean,
        sensorBatchLatencyMs: Int,
        sensors: List<SensorDescriptor>
    ): File {
        val actionSlug = actionSlug(config.actionLabel)
        val dir = createSessionDir(config.actionLabel, id)
        val json = buildString {
            appendLine("{")
            appendLine("  \"schema_version\": 2,")
            appendLine("  \"session_id\": ${JsonUtil.esc(id)},")
            appendLine("  \"session_name\": ${JsonUtil.esc(config.sessionName)},")
            appendLine("  \"subject_id\": ${JsonUtil.esc(config.subjectId)},")
            appendLine("  \"action_label\": ${JsonUtil.esc(config.actionLabel)},")
            appendLine("  \"action_slug\": ${JsonUtil.esc(actionSlug)},")
            appendLine("  \"wrist\": ${JsonUtil.esc(config.wrist)},")
            appendLine("  \"notes\": ${JsonUtil.esc(config.notes)},")
            appendLine("  \"sampling_mode\": ${JsonUtil.esc(config.samplingMode)},")
            appendLine("  \"requested_sample_rate_hz\": ${config.sampleRateHz},")
            appendLine("  \"sensor_batch_latency_ms\": $sensorBatchLatencyMs,")
            appendLine("  \"auto_stop_minutes\": ${config.autoStopMinutes},")
            appendLine("  \"started_utc\": ${JsonUtil.esc(startedUtc)},")
            appendLine("  \"started_wall_time_ms\": $startedWallMs,")
            appendLine("  \"started_elapsed_realtime_ns\": $startedElapsedNs,")
            appendLine("  \"clock_mapping\": \"wall_time_ms = started_wall_time_ms + (sensor_timestamp_ns - started_elapsed_realtime_ns)/1000000\",")
            appendLine("  \"battery_start_pct\": $batteryPct,")
            appendLine("  \"charging_start\": $charging,")
            appendLine("  \"app\": {")
            appendLine("    \"package\": ${JsonUtil.esc(BuildConfig.APPLICATION_ID)},")
            appendLine("    \"version_name\": ${JsonUtil.esc(BuildConfig.VERSION_NAME)},")
            appendLine("    \"version_code\": ${BuildConfig.VERSION_CODE}")
            appendLine("  },")
            appendLine("  \"device\": {")
            appendLine("    \"manufacturer\": ${JsonUtil.esc(Build.MANUFACTURER)},")
            appendLine("    \"brand\": ${JsonUtil.esc(Build.BRAND)},")
            appendLine("    \"model\": ${JsonUtil.esc(Build.MODEL)},")
            appendLine("    \"device\": ${JsonUtil.esc(Build.DEVICE)},")
            appendLine("    \"product\": ${JsonUtil.esc(Build.PRODUCT)},")
            appendLine("    \"android_release\": ${JsonUtil.esc(Build.VERSION.RELEASE)},")
            appendLine("    \"sdk_int\": ${Build.VERSION.SDK_INT}")
            appendLine("  },")
            appendLine("  \"sensors\": [")
            sensors.forEachIndexed { index, s ->
                append("    {")
                append("\"type\":${s.type},")
                append("\"string_type\":${JsonUtil.esc(s.stringType)},")
                append("\"name\":${JsonUtil.esc(s.name)},")
                append("\"vendor\":${JsonUtil.esc(s.vendor)},")
                append("\"version\":${s.version},")
                append("\"resolution\":${s.resolution},")
                append("\"max_range\":${s.maxRange},")
                append("\"min_delay_us\":${s.minDelayUs},")
                append("\"max_delay_us\":${s.maxDelayUs},")
                append("\"fifo_max_event_count\":${s.fifoMaxEventCount},")
                append("\"fifo_reserved_event_count\":${s.fifoReservedEventCount},")
                append("\"wake_up\":${s.wakeUp},")
                append("\"reporting_mode\":${s.reportingMode}")
                append('}')
                if (index != sensors.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"ended_utc\": null,")
            appendLine("  \"duration_seconds\": null,")
            appendLine("  \"event_count\": 0,")
            appendLine("  \"events_by_sensor\": {},")
            appendLine("  \"stop_reason\": null,")
            appendLine("  \"battery_end_pct\": null,")
            appendLine("  \"charging_end\": null")
            appendLine("}")
        }
        atomicWrite(File(dir, "metadata.json"), json)
        return dir
    }

    fun finalizeMetadata(
        id: String,
        endedUtc: String,
        durationSeconds: Long,
        eventCount: Long,
        eventsBySensor: Map<String, Long>,
        stopReason: String,
        batteryPct: Int,
        charging: Boolean
    ) {
        val dir = findSessionDir(id) ?: return
        val file = File(dir, "metadata.json")
        if (!file.exists()) return
        val sensorCountsJson = eventsBySensor.entries.sortedBy { it.key }.joinToString(",") {
            "${JsonUtil.esc(it.key)}:${it.value}"
        }
        var text = file.readText()
        text = text.replace("\"ended_utc\": null", "\"ended_utc\": ${JsonUtil.esc(endedUtc)}")
            .replace("\"duration_seconds\": null", "\"duration_seconds\": $durationSeconds")
            .replace("\"event_count\": 0", "\"event_count\": $eventCount")
            .replace("\"events_by_sensor\": {}", "\"events_by_sensor\": {$sensorCountsJson}")
            .replace("\"stop_reason\": null", "\"stop_reason\": ${JsonUtil.esc(stopReason)}")
            .replace("\"battery_end_pct\": null", "\"battery_end_pct\": $batteryPct")
            .replace("\"charging_end\": null", "\"charging_end\": $charging")
        atomicWrite(file, text)
    }

    fun exportAll(): File {
        val exportRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "ActionLogger/exports").apply { mkdirs() }
        val timestamp = Instant.now().toString().replace(':', '-')
        val out = File(exportRoot, "ActionLogger_dataset_$timestamp.zip")
        val sessions = listSessions()

        ZipOutputStream(FileOutputStream(out).buffered(64 * 1024)).use { zip ->
            sessionsRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(sessionsRoot).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry("ActionLogger/$relative"))
                FileInputStream(file).use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }

            val labels = sessions.groupingBy { it.actionLabel }.eachCount().toSortedMap()
            val labelsJson = labels.entries.joinToString(",") { "${JsonUtil.esc(it.key)}:${it.value}" }
            val manifest = buildString {
                appendLine("{")
                appendLine("  \"schema_version\": 2,")
                appendLine("  \"exported_utc\": ${JsonUtil.esc(Instant.now().toString())},")
                appendLine("  \"app_version\": ${JsonUtil.esc(BuildConfig.VERSION_NAME)},")
                appendLine("  \"session_count\": ${sessions.size},")
                appendLine("  \"sessions_by_action\": {$labelsJson},")
                appendLine("  \"layout\": \"ActionLogger/<action_slug>/<session_id>/\",")
                appendLine("  \"event_file\": \"sensor_events.csv\",")
                appendLine("  \"metadata_file\": \"metadata.json\",")
                appendLine("  \"marker_file\": \"markers.csv\"")
                appendLine("}")
            }
            zip.putNextEntry(ZipEntry("ActionLogger/DATASET_MANIFEST.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out
    }

    private fun readSummary(dir: File): SessionSummary? {
        val file = File(dir, "metadata.json")
        if (!file.exists()) return null
        val text = file.readText()
        fun str(key: String): String? = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(text)?.groupValues?.get(1)
        fun number(key: String): Long? = Regex("\\\"$key\\\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        return SessionSummary(
            id = str("session_id") ?: dir.name,
            name = str("session_name") ?: dir.name,
            actionLabel = str("action_label") ?: dir.parentFile?.name ?: "unknown",
            actionSlug = str("action_slug") ?: dir.parentFile?.name ?: "unknown",
            subjectId = str("subject_id") ?: "unknown",
            wrist = str("wrist") ?: "unknown",
            startedUtc = str("started_utc") ?: "",
            endedUtc = str("ended_utc"),
            durationSeconds = number("duration_seconds"),
            sampleRateHz = number("requested_sample_rate_hz")?.toInt() ?: 0,
            samplingMode = str("sampling_mode") ?: "unknown",
            eventCount = number("event_count") ?: countEvents(File(dir, "sensor_events.csv")),
            stopReason = str("stop_reason"),
            path = dir.absolutePath
        )
    }

    private fun countEvents(file: File): Long {
        if (!file.exists()) return 0
        BufferedReader(InputStreamReader(FileInputStream(file))).use { reader ->
            var count = -1L
            while (reader.readLine() != null) count++
            return count.coerceAtLeast(0)
        }
    }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(file)) {
            file.writeText(content)
            temp.delete()
        }
    }
}
