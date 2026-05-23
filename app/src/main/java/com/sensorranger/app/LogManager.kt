package com.sensorranger.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

object LogManager {

    private const val MAX_ENTRIES = 60
    private const val PREF_NAME = "sensor_ranger_log"
    private const val KEY_LOG = "log"
    private const val KEY_CRASH = "last_crash"

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message"
        val existing = prefs.getString(KEY_LOG, "") ?: ""
        val lines = existing.split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add(line)
        if (lines.size > MAX_ENTRIES) lines.removeAt(0)
        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun getLog(): String = prefs.getString(KEY_LOG, "") ?: ""

    fun clearLog() = prefs.edit().putString(KEY_LOG, "").apply()

    fun saveCrash(trace: String) {
        val header = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"
        prefs.edit().putString(KEY_CRASH, header + trace).apply()
    }

    fun getLastCrash(): String? {
        val c = prefs.getString(KEY_CRASH, null)
        return if (c.isNullOrBlank()) null else c
    }

    fun clearCrash() = prefs.edit().remove(KEY_CRASH).apply()
}
