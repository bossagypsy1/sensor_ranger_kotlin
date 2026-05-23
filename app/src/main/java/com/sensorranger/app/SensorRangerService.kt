package com.sensorranger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
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
        const val ACTION_START = "com.sensorranger.app.START"
        const val ACTION_STOP = "com.sensorranger.app.STOP"
        const val ACTION_STATUS = "com.sensorranger.app.STATUS"
        const val EXTRA_LAST_RESULT = "lastResult"
        const val EXTRA_LAST_PUSH = "lastPush"
        const val EXTRA_RETRY_COUNT = "retryCount"
        const val NOTIF_CHANNEL = "sensor_ranger_fg"
        const val NOTIF_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pushJob: Job? = null
    private lateinit var prefs: PreferencesManager
    private lateinit var sensorCollector: SensorCollector
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also {
        it.timeZone = TimeZone.getTimeZone("UTC")
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            sensorCollector.latestLocation = result.lastLocation
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        sensorCollector = SensorCollector(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startPushing()
            ACTION_STOP -> {
                stopPushing()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopPushing()
        serviceScope.cancel()
    }

    private fun startPushing() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        sensorCollector.start()
        startLocationUpdates()
        scheduleLoop()
    }

    private fun stopPushing() {
        pushJob?.cancel()
        pushJob = null
        sensorCollector.stop()
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    private fun scheduleLoop() {
        pushJob?.cancel()
        val intervalMs = prefs.frequencyMs
        pushJob = serviceScope.launch {
            // Immediate first push
            performPush()
            while (isActive) {
                delay(intervalMs)
                performPush()
            }
        }
    }

    private suspend fun performPush() {
        val sessionId = prefs.sessionId
        if (sessionId.isEmpty()) return

        val deviceId = prefs.deviceId
        val messageId = prefs.nextMessageId()
        val payload = sensorCollector.buildPayload(prefs)

        if (payload.length() == 0) return

        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("messageId", messageId)
            put("sessionId", sessionId)
            put("payload", payload)
        }.toString()

        val token = if (prefs.useBearerToken && prefs.apiToken.isNotEmpty()) prefs.apiToken else null
        val result = ApiClient.post(prefs.apiUrl, body, token)

        val resultStr = if (result.success) "HTTP ${result.status}"
                        else result.error ?: "HTTP ${result.status}"
        val nowStr = isoFormat.format(Date())

        prefs.lastPush = nowStr
        prefs.lastResult = resultStr
        if (result.success) prefs.retryCount = 0
        else prefs.retryCount = prefs.retryCount + 1

        // Broadcast status to activity
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_LAST_RESULT, resultStr)
            putExtra(EXTRA_LAST_PUSH, nowStr)
            putExtra(EXTRA_RETRY_COUNT, prefs.retryCount)
        })
    }

    private fun startLocationUpdates() {
        try {
            val req = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L
            ).setWaitForAccurateLocation(false).build()
            fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {}
    }

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
