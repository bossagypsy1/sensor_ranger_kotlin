package com.sensorranger.app

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogManager {

    private const val MAX_LOG_LINES = 200
    private const val LOG_FILE   = "sensor_ranger.log"
    private const val CRASH_FILE = "sensor_ranger_crash.log"

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private lateinit var appContext: Context

    // In-memory ring buffer — all mutations/reads under `lock`
    private val lock   = Any()
    private val buffer = ArrayDeque<String>(MAX_LOG_LINES + 1)

    // Debounced file flush: cancelled and rescheduled on each log() call so the
    // file is written 2 s after the last entry, not on every single log line.
    private val flushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    fun init(context: Context) {
        appContext = context.applicationContext
        // Seed buffer from disk so the in-app log view shows previous entries
        synchronized(lock) {
            try {
                logFile().takeIf { it.exists() }
                    ?.readLines()
                    ?.takeLast(MAX_LOG_LINES)
                    ?.forEach { buffer.addLast(it) }
            } catch (_: Exception) {}
        }
    }

    // -------------------------------------------------------------------------
    // Log
    // -------------------------------------------------------------------------

    fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message"
        android.util.Log.d("SensorRanger", line)

        synchronized(lock) {
            buffer.addLast(line)
            if (buffer.size > MAX_LOG_LINES) buffer.removeFirst()
        }

        // Debounce: cancel any pending flush and schedule a fresh one in 2 s
        flushJob?.cancel()
        flushJob = flushScope.launch {
            delay(2_000L)
            val snapshot = synchronized(lock) { buffer.toList() }
            try { logFile().writeText(snapshot.joinToString("\n")) } catch (_: Exception) {}
        }
    }

    fun getLog(): String = synchronized(lock) {
        if (buffer.isEmpty()) "(no log entries yet)" else buffer.joinToString("\n")
    }

    fun clearLog() {
        synchronized(lock) { buffer.clear() }
        flushJob?.cancel()
        try { logFile().delete() } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Crash
    // -------------------------------------------------------------------------

    fun saveCrash(throwable: Throwable) {
        val text = buildString {
            append("CRASHED: ${dateFmt.format(Date())}\n")
            append("${throwable.javaClass.name}: ${throwable.message}\n\n")
            append(throwable.stackTraceToString())
        }
        saveCrashText(text)
        log("CRASH", "${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    fun saveCrashText(text: String) = try { crashFile().writeText(text) } catch (_: Exception) {}

    fun getLastCrash(): String? = try {
        crashFile().takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    fun clearCrash() = try { crashFile().delete() } catch (_: Exception) { false }
    fun hasCrash()   = try { crashFile().exists() } catch (_: Exception) { false }

    // -------------------------------------------------------------------------
    // File paths
    // -------------------------------------------------------------------------

    fun logFile():   File = File(appContext.filesDir, LOG_FILE)
    fun crashFile(): File = File(appContext.filesDir, CRASH_FILE)
}
