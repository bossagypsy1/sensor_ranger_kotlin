package com.sensorranger.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sensor_ranger", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_API_URL =
            "https://remote-sensor-phnx-refactor.vercel.app/api/ingest/mobile_phone"

        // Fixed device ID — provisioned to match the receiving app.
        // Change this before building if deploying to a different device.
        const val DEFAULT_DEVICE_ID = "4df4073a-e032-4333-b542-6cca78d502d5"

        val FREQUENCY_OPTIONS = listOf(
            FrequencyOption("1 minute", 60_000L),
            FrequencyOption("5 minutes", 300_000L),
            FrequencyOption("10 minutes", 600_000L),
            FrequencyOption("30 minutes", 1_800_000L),
            FrequencyOption("1 hour", 3_600_000L),
            FrequencyOption("8 hours", 28_800_000L),
            FrequencyOption("1 day", 86_400_000L)
        )

        val SENSOR_KEYS = listOf(
            SensorToggle("location", "Location", true),
            SensorToggle("battery", "Battery", true),
            SensorToggle("accelerometer", "Accelerometer", true),
            SensorToggle("gravity", "Gravity", false),
            SensorToggle("gyroscope", "Gyroscope", true),
            SensorToggle("orientation", "Orientation", false),
            SensorToggle("magnetometer", "Magnetometer", true),
            SensorToggle("compass", "Compass", true),
            SensorToggle("barometer", "Barometer", true),
            SensorToggle("totalAcceleration", "TotalAcceleration", false),
            SensorToggle("magnetometerUncalibrated", "MagnetometerUncalibrated", false),
            SensorToggle("gyroscopeUncalibrated", "GyroscopeUncalibrated", false),
            SensorToggle("accelerometerUncalibrated", "AccelerometerUncalibrated", false)
        )
    }

    data class FrequencyOption(val label: String, val ms: Long)
    data class SensorToggle(val key: String, val label: String, val defaultEnabled: Boolean)

    var deviceId: String
        get() = prefs.getString("deviceId", null) ?: DEFAULT_DEVICE_ID.also { deviceId = it }
        set(v) = prefs.edit().putString("deviceId", v).apply()

    var apiUrl: String
        get() = prefs.getString("apiUrl", DEFAULT_API_URL) ?: DEFAULT_API_URL
        set(v) = prefs.edit().putString("apiUrl", v).apply()

    var apiToken: String
        get() = prefs.getString("apiToken", "") ?: ""
        set(v) = prefs.edit().putString("apiToken", v).apply()

    var useBearerToken: Boolean
        get() = prefs.getBoolean("useBearerToken", false)
        set(v) = prefs.edit().putBoolean("useBearerToken", v).apply()

    var messageId: Long
        get() = prefs.getLong("messageId", 0L)
        set(v) = prefs.edit().putLong("messageId", v).apply()

    var sessionId: String
        get() = prefs.getString("sessionId", "") ?: ""
        set(v) = prefs.edit().putString("sessionId", v).apply()

    var frequencyMs: Long
        get() = prefs.getLong("frequencyMs", FREQUENCY_OPTIONS[0].ms)
        set(v) = prefs.edit().putLong("frequencyMs", v).apply()

    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(v) = prefs.edit().putBoolean("running", v).apply()

    var lastPush: String
        get() = prefs.getString("lastPush", "") ?: ""
        set(v) = prefs.edit().putString("lastPush", v).apply()

    var lastResult: String
        get() = prefs.getString("lastResult", "") ?: ""
        set(v) = prefs.edit().putString("lastResult", v).apply()

    var retryCount: Int
        get() = prefs.getInt("retryCount", 0)
        set(v) = prefs.edit().putInt("retryCount", v).apply()

    fun isSensorEnabled(key: String): Boolean {
        val default = SENSOR_KEYS.find { it.key == key }?.defaultEnabled ?: false
        return prefs.getBoolean("sensor_$key", default)
    }

    fun setSensorEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean("sensor_$key", enabled).apply()
    }

    fun nextMessageId(): Long {
        val next = messageId + 1
        messageId = next
        return next
    }
}
