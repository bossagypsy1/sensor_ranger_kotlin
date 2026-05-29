package com.sensorranger.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogManager {

    private const val MAX_LOG_LINES = 200
    private const val LOG_FILE = "sensor_ranger.log"
    private const val CRASH_FILE = "sensor_ranger_crash.log"

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // -------------------------------------------------------------------------
    // Log
    // -------------------------------------------------------------------------

    fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message"
        android.util.Log.d("SensorRanger", line) // also goes to logcat

        try {
            val file = logFile()
            val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
            lines.add(line)
            if (lines.size > MAX_LOG_LINES) lines.subList(0, lines.size - MAX_LOG_LINES).clear()
            file.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {}
    }

    fun getLog(): String = try {
        logFile().takeIf { it.exists() }?.readText() ?: "(no log yet)"
    } catch (_: Exception) { "(error reading log)" }

    fun clearLog() = try { logFile().delete() } catch (_: Exception) { false }

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

    fun hasCrash() = try { crashFile().exists() } catch (_: Exception) { false }

    // -------------------------------------------------------------------------
    // File paths
    // -------------------------------------------------------------------------

    fun logFile(): File = File(appContext.filesDir, LOG_FILE)
    fun crashFile(): File = File(appContext.filesDir, CRASH_FILE)
}
