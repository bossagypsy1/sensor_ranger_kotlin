package com.sensorranger.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class SensorRangerService : Service() {

    companion object {
        const val ACTION_START  = "com.sensorranger.app.START"
        const val ACTION_STOP   = "com.sensorranger.app.STOP"
        const val ACTION_PUSH   = "com.sensorranger.app.PUSH"   // fired by PushAlarmReceiver
        const val ACTION_STATUS = "com.sensorranger.app.STATUS"
        const val EXTRA_LAST_RESULT = "lastResult"
        const val EXTRA_LAST_PUSH   = "lastPush"
        const val EXTRA_RETRY_COUNT = "retryCount"
        const val NOTIF_CHANNEL     = "sensor_ranger_fg"
        const val NOTIF_ID          = 1001
        private const val ALARM_REQUEST_CODE = 1337
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PreferencesManager
    private lateinit var sensorCollector: SensorCollector
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /** True once startPushing() has set up foreground + sensors + location. */
    private var isPushing = false

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also {
        it.timeZone = TimeZone.getTimeZone("UTC")
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            sensorCollector.latestLocation = result.lastLocation
            LogManager.log("LOC", "lat=${result.lastLocation?.latitude} lon=${result.lastLocation?.longitude}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        sensorCollector = SensorCollector(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        LogManager.log("SERVICE", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogManager.log("SERVICE", "onStartCommand action=${intent?.action}")

        // Android requires startForeground() within 5s of startForegroundService().
        // Call it immediately — before any async work — so the deadline is always met.
        if (intent?.action != ACTION_STOP) {
            callStartForeground()
        }

        when (intent?.action) {
            ACTION_START -> startPushing()

            ACTION_STOP -> {
                stopPushing()
                stopSelf()
            }

            ACTION_PUSH -> {
                // Alarm fired. If the service was killed and restarted by the alarm,
                // isPushing will be false — call startPushing() which handles setup.
                // If already running normally, just push and reschedule.
                if (prefs.running) {
                    if (!isPushing) {
                        LogManager.log("SERVICE", "Alarm woke killed service — restarting")
                        startPushing()
                    } else {
                        serviceScope.launch {
                            performPush()
                            scheduleNextAlarm()
                        }
                    }
                }
            }

            null -> {
                // START_STICKY: Android restarted the service after killing it.
                // Resume automatically if the user had it running.
                if (prefs.running) {
                    LogManager.log("SERVICE", "Restarted by OS — resuming")
                    startPushing()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    /**
     * Calls startForeground() to satisfy Android's 5-second ANR deadline.
     * Falls back without the location type if the permission is denied, rather than
     * catching the error silently and letting the timer expire (which causes the crash).
     */
    private fun callStartForeground() {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            LogManager.log("SERVICE", "startForeground OK (location type)")
        } catch (e: Exception) {
            LogManager.log("ERROR", "startForeground (location) failed: ${e.message} — retrying without type")
            try {
                startForeground(NOTIF_ID, buildNotification())
                LogManager.log("SERVICE", "startForeground OK (no type fallback)")
            } catch (e2: Exception) {
                LogManager.log("ERROR", "startForeground failed completely: ${e2.message}")
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        LogManager.log("SERVICE", "onDestroy")
        stopPushing()
        serviceScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Start / stop
    // -------------------------------------------------------------------------

    private fun startPushing() {
        // startForeground() is already called in onStartCommand via callStartForeground().
        isPushing = true
        sensorCollector.start()
        startLocationUpdates()
        // Brief warm-up delay so sensors and last-location callback have time to arrive
        serviceScope.launch {
            delay(2_000L)
            performPush()
            scheduleNextAlarm()
        }
    }

    private fun stopPushing() {
        isPushing = false
        cancelAlarm()
        sensorCollector.stop()
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        LogManager.log("SERVICE", "Stopped")
    }

    // -------------------------------------------------------------------------
    // AlarmManager scheduling
    //
    // setAndAllowWhileIdle() fires during Doze maintenance windows so pushes
    // still happen even when the phone has been idle for hours.
    // It does NOT require SCHEDULE_EXACT_ALARM permission (unlike the exact
    // variant on API 31+). The ±9 min jitter is fine for hourly sensor pushes.
    // -------------------------------------------------------------------------

    private fun scheduleNextAlarm() {
        val am = getSystemService(AlarmManager::class.java)
        val pi = alarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?: return
        val triggerAt = System.currentTimeMillis() + prefs.frequencyMs
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        LogManager.log("ALARM", "Next push in ${prefs.frequencyMs / 1000}s at ${isoFormat.format(Date(triggerAt))}")
    }

    private fun cancelAlarm() {
        val am = getSystemService(AlarmManager::class.java)
        val pi = alarmPendingIntent(PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        pi?.let { am.cancel(it); LogManager.log("ALARM", "Alarm cancelled") }
    }

    private fun alarmPendingIntent(flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            this, ALARM_REQUEST_CODE,
            Intent(this, PushAlarmReceiver::class.java),
            flags
        )

    // -------------------------------------------------------------------------
    // Push
    // -------------------------------------------------------------------------

    private suspend fun performPush() {
        try {
            val sessionId = prefs.sessionId
            if (sessionId.isEmpty()) {
                LogManager.log("PUSH", "Skipped — no session")
                return
            }

            val deviceId  = prefs.deviceId
            val messageId = prefs.nextMessageId()
            val payload   = sensorCollector.buildPayload(prefs)

            LogManager.log("PUSH", "msg=$messageId sensors=${payload.length()}")

            if (payload.length() == 0) {
                LogManager.log("PUSH", "Skipped — no sensor data")
                return
            }

            val body = JSONObject().apply {
                put("deviceId",  deviceId)
                put("messageId", messageId)
                put("sessionId", sessionId)
                put("payload",   payload)
            }.toString()

            val token  = if (prefs.useBearerToken && prefs.apiToken.isNotEmpty()) prefs.apiToken else null
            val result = ApiClient.post(prefs.apiUrl, body, token)

            val resultStr = if (result.success) "HTTP ${result.status}"
                            else result.error ?: "HTTP ${result.status}"
            val nowStr = isoFormat.format(Date())

            LogManager.log("PUSH", "Result: $resultStr")

            prefs.lastPush   = nowStr
            prefs.lastResult = resultStr
            if (result.success) prefs.retryCount = 0
            else prefs.retryCount = prefs.retryCount + 1

            sendBroadcast(Intent(ACTION_STATUS).apply {
                putExtra(EXTRA_LAST_RESULT,  resultStr)
                putExtra(EXTRA_LAST_PUSH,    nowStr)
                putExtra(EXTRA_RETRY_COUNT,  prefs.retryCount)
            })
        } catch (e: Exception) {
            LogManager.log("ERROR", "performPush: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Location
    // -------------------------------------------------------------------------

    private fun startLocationUpdates() {
        try {
            // Seed latestLocation immediately with the last known cached fix
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    sensorCollector.latestLocation = location
                    LogManager.log("LOC", "Last known: lat=${location.latitude} lon=${location.longitude}")
                } else {
                    LogManager.log("LOC", "No last known location cached")
                }
            }
            // Continuous updates every 10s for fresh fixes
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(5_000L)
                .build()
            fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            LogManager.log("LOC", "Location updates started")
        } catch (e: SecurityException) {
            LogManager.log("ERROR", "Location permission denied: ${e.message}")
        } catch (e: Exception) {
            LogManager.log("ERROR", "Location updates failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL, getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Sensor data push service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
