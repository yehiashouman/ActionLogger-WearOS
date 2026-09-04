package com.yehia.actionlogger.data

data class SessionConfig(
    val subjectId: String,
    val sessionName: String,
    val actionLabel: String,
    val wrist: String,
    val sampleRateHz: Int,
    val samplingMode: String,
    val autoStopMinutes: Int,
    val notes: String
)

data class SessionSummary(
    val id: String,
    val name: String,
    val actionLabel: String,
    val actionSlug: String,
    val subjectId: String,
    val wrist: String,
    val startedUtc: String,
    val endedUtc: String?,
    val durationSeconds: Long?,
    val sampleRateHz: Int,
    val samplingMode: String,
    val eventCount: Long,
    val stopReason: String?,
    val path: String
)

data class SensorDescriptor(
    val type: Int,
    val stringType: String,
    val name: String,
    val vendor: String,
    val version: Int,
    val resolution: Float,
    val maxRange: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val fifoMaxEventCount: Int,
    val fifoReservedEventCount: Int,
    val wakeUp: Boolean,
    val reportingMode: Int
)
