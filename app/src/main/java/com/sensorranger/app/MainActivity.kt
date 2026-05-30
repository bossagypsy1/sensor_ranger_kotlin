package com.sensorranger.app

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.gms.location.LocationServices
import com.sensorranger.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    private var running = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var lastPushMs = 0L

    private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also {
        it.timeZone = TimeZone.getTimeZone("UTC")
    }

    // Sensor switch references keyed by sensor key
    private val sensorSwitches = mutableMapOf<String, SwitchMaterial>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val result = intent.getStringExtra(SensorRangerService.EXTRA_LAST_RESULT) ?: ""
            val push = intent.getStringExtra(SensorRangerService.EXTRA_LAST_PUSH) ?: ""
            val retry = intent.getIntExtra(SensorRangerService.EXTRA_RETRY_COUNT, 0)
            updateStatus(push, result, retry)
            refreshLog()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionBadges() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            prefs = PreferencesManager(this)

            setupApiSettings()
            setupDevice()
            setupFrequency()
            setupSensorToggles()
            setupControls()
            setupStatus()
            setupLog()
            loadStatus()
            checkForCrash()
            requestPermissions()
        } catch (e: Exception) {
            LogManager.saveCrash(e)
            val msg = "${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString()}"
            AlertDialog.Builder(this)
                .setTitle("Startup error")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(SensorRangerService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED)
        loadStatus()
        updatePermissionBadges()
        refreshLog()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        countdownRunnable?.let { uiHandler.removeCallbacks(it) }
    }

    // -------------------------------------------------------------------------
    // Setup sections
    // -------------------------------------------------------------------------

    private fun setupApiSettings() {
        binding.etApiUrl.setText(prefs.apiUrl)
        binding.etApiToken.setText(prefs.apiToken)
        binding.swBearerToken.isChecked = prefs.useBearerToken

        binding.etApiUrl.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                prefs.apiUrl = s.toString().trim()
            }
        })
        binding.etApiToken.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                prefs.apiToken = s.toString().trim()
            }
        })
        binding.swBearerToken.setOnCheckedChangeListener { _, checked ->
            prefs.useBearerToken = checked
        }
    }

    private fun setupDevice() {
        binding.tvDeviceId.text = prefs.deviceId
        binding.btnCopyDeviceId.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Device ID", prefs.deviceId))
            Toast.makeText(this, "Device ID copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFrequency() {
        val options = PreferencesManager.FREQUENCY_OPTIONS
        val adapter = ArrayAdapter(this, R.layout.spinner_item, options.map { it.label }).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        binding.spFrequency.adapter = adapter

        val currentIndex = options.indexOfFirst { it.ms == prefs.frequencyMs }.coerceAtLeast(0)
        binding.spFrequency.setSelection(currentIndex)

        binding.spFrequency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                prefs.frequencyMs = options[pos].ms
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupSensorToggles() {
        val container = binding.llSensors
        container.removeAllViews()

        val dp1 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics).toInt()
        val dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()

        PreferencesManager.SENSOR_KEYS.forEach { toggle ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp6, 0, dp6)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(Color.TRANSPARENT)
            }

            val label = TextView(this).apply {
                text = toggle.label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val sw = SwitchMaterial(this).apply {
                isChecked = prefs.isSensorEnabled(toggle.key)
                setOnCheckedChangeListener { _, checked ->
                    prefs.setSensorEnabled(toggle.key, checked)
                }
            }

            // Divider
            val divider = android.view.View(this).apply {
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.border))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp1)
            }

            row.addView(label)
            row.addView(sw)
            container.addView(row)
            container.addView(divider)
            sensorSwitches[toggle.key] = sw
        }
    }

    private fun setupControls() {
        binding.btnTestPush.setOnClickListener { sendTestPush() }
        binding.btnStart.setOnClickListener { startService() }
        binding.btnStop.setOnClickListener { stopService() }
        syncControlState()
    }

    private fun setupStatus() {
        binding.rowRunning.tvLabel.text = "Running"
        binding.rowLastPush.tvLabel.text = "Last Push"
        binding.rowLastResult.tvLabel.text = "Last Result"
        binding.rowNextPush.tvLabel.text = "Next Push"
        binding.rowRetryCount.tvLabel.text = "Retry Count"
    }

    // -------------------------------------------------------------------------
    // Service control
    // -------------------------------------------------------------------------

    private fun startService() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show()
            requestPermissions()
            return
        }
        // Prompt once to exempt from battery optimisation — the single most effective
        // fix for OEM battery killers stopping the foreground service overnight.
        promptBatteryOptimisationExemption()

        prefs.sessionId = "session-${UUID.randomUUID()}-${System.currentTimeMillis()}"
        prefs.running = true
        running = true
        LogManager.log("UI", "Start Service pressed — interval=${prefs.frequencyMs/1000}s")
        syncControlState()

        ContextCompat.startForegroundService(this,
            Intent(this, SensorRangerService::class.java).apply {
                action = SensorRangerService.ACTION_START
            })
    }

    private fun promptBatteryOptimisationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return   // already exempted
        AlertDialog.Builder(this)
            .setTitle("Keep service running overnight?")
            .setMessage(
                "Android's battery optimisation can stop the push service after a few hours.\n\n" +
                "Tap OK to disable battery optimisation for Sensor Ranger — this is the most " +
                "reliable way to ensure hourly pushes continue while the phone is idle."
            )
            .setPositiveButton("OK") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    LogManager.log("PERM", "Battery opt intent failed: ${e.message}")
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun stopService() {
        prefs.running = false
        prefs.sessionId = ""
        running = false
        LogManager.log("UI", "Stop Service pressed")
        syncControlState()

        startService(Intent(this, SensorRangerService::class.java).apply {
            action = SensorRangerService.ACTION_STOP
        })
        countdownRunnable?.let { uiHandler.removeCallbacks(it) }
        binding.rowRunning.tvValue.text = "No"
        binding.rowRunning.tvValue.setTextColor(ContextCompat.getColor(this, R.color.red))
        binding.rowNextPush.tvValue.text = "—"
    }

    private fun sendTestPush() {
        if (!hasLocationPermission()) {
            requestPermissions()
        }
        binding.btnTestPush.isEnabled = false
        binding.btnTestPush.text = "Sending…"

        // Ensure a session ID exists for the test
        if (prefs.sessionId.isEmpty()) {
            prefs.sessionId = "test-session-${UUID.randomUUID()}-${System.currentTimeMillis()}"
        }

        CoroutineScope(Dispatchers.IO).launch {
            val collector = SensorCollector(this@MainActivity)
            collector.start()

            // Seed last-known location so the test payload includes GPS
            if (hasLocationPermission()) {
                try {
                    val locDeferred = CompletableDeferred<android.location.Location?>()
                    val fusedClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
                    fusedClient.lastLocation
                        .addOnSuccessListener { loc -> locDeferred.complete(loc) }
                        .addOnFailureListener { locDeferred.complete(null) }
                    val location = withTimeoutOrNull(2_000L) { locDeferred.await() }
                    if (location != null) {
                        collector.latestLocation = location
                        LogManager.log("TEST", "Location seeded: lat=${location.latitude} lon=${location.longitude}")
                    } else {
                        LogManager.log("TEST", "No cached location available for test push")
                    }
                } catch (e: Exception) {
                    LogManager.log("TEST", "Location fetch failed: ${e.message}")
                }
            }

            delay(800) // Brief delay for first sensor readings
            val payload = collector.buildPayload(prefs)
            LogManager.log("TEST", "Payload has ${payload.length()} sensors")
            collector.stop()

            if (!prefs.running) prefs.sessionId = ""

            if (payload.length() == 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No sensors available", Toast.LENGTH_SHORT).show()
                    binding.btnTestPush.isEnabled = true
                    binding.btnTestPush.text = "Send Test Payload"
                }
                return@launch
            }

            val deviceId = prefs.deviceId
            val messageId = prefs.nextMessageId()
            val body = org.json.JSONObject().apply {
                put("deviceId", deviceId)
                put("messageId", messageId)
                put("sessionId", prefs.sessionId)
                put("payload", payload)
            }.toString()

            val token = if (prefs.useBearerToken && prefs.apiToken.isNotEmpty()) prefs.apiToken else null
            val result = ApiClient.post(prefs.apiUrl, body, token)
            val resultStr = if (result.success) "HTTP ${result.status}" else result.error ?: "HTTP ${result.status}"
            val nowStr = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also {
                it.timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            prefs.lastPush = nowStr
            prefs.lastResult = resultStr
            if (result.success) prefs.retryCount = 0 else prefs.retryCount = prefs.retryCount + 1

            withContext(Dispatchers.Main) {
                updateStatus(nowStr, resultStr, prefs.retryCount)
                binding.btnTestPush.isEnabled = true
                binding.btnTestPush.text = "Send Test Payload"
            }
        }
    }

    private fun syncControlState() {
        running = prefs.running
        binding.btnStart.isEnabled = !running
        binding.btnStart.alpha = if (!running) 1f else 0.4f
        binding.btnStop.isEnabled = running
        binding.btnStop.alpha = if (running) 1f else 0.4f
    }

    // -------------------------------------------------------------------------
    // Status display
    // -------------------------------------------------------------------------

    private fun loadStatus() {
        syncControlState()
        binding.rowRunning.tvValue.text = if (running) "Yes" else "No"
        binding.rowRunning.tvValue.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.green else R.color.red)
        )
        val lp = prefs.lastPush
        val lr = prefs.lastResult
        binding.rowLastPush.tvValue.text = if (lp.isEmpty()) "—" else formatTime(lp)
        binding.rowLastResult.tvValue.text = if (lr.isEmpty()) "—" else lr
        binding.rowRetryCount.tvValue.text = prefs.retryCount.toString()

        if (lp.isNotEmpty()) {
            try {
                lastPushMs = isoParser.parse(lp)?.time ?: 0L
                startCountdown()
            } catch (_: Exception) {}
        } else {
            binding.rowNextPush.tvValue.text = "—"
        }
    }

    private fun updateStatus(lastPush: String, lastResult: String, retry: Int) {
        binding.rowLastPush.tvValue.text = formatTime(lastPush)
        binding.rowLastResult.tvValue.text = lastResult
        binding.rowRetryCount.tvValue.text = retry.toString()
        try {
            lastPushMs = isoParser.parse(lastPush)?.time ?: 0L
            startCountdown()
        } catch (_: Exception) {}
    }

    private fun startCountdown() {
        countdownRunnable?.let { uiHandler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (!running) { binding.rowNextPush.tvValue.text = "—"; return }
                val nextMs = lastPushMs + prefs.frequencyMs
                val diffMs = nextMs - System.currentTimeMillis()
                binding.rowNextPush.tvValue.text = when {
                    diffMs <= 0 -> "Now"
                    diffMs < 60_000 -> "${diffMs / 1000}s"
                    diffMs < 3_600_000 -> "${diffMs / 60_000}m ${(diffMs % 60_000) / 1000}s"
                    else -> "${diffMs / 3_600_000}h ${(diffMs % 3_600_000) / 60_000}m"
                }
                uiHandler.postDelayed(this, 1000)
            }
        }
        countdownRunnable = tick
        uiHandler.post(tick)
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun requestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun updatePermissionBadges() {
        val container = binding.llPermissions
        container.removeAllViews()

        fun badge(label: String, granted: Boolean) {
            val tv = TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, if (granted) R.color.accent else R.color.red))
                setBackgroundColor(if (granted) ContextCompat.getColor(this@MainActivity, R.color.accent_dim) else 0x3FF44336.toInt())
                setPadding(16, 6, 16, 6)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 8 }
                layoutParams = lp
                // Rounded corners via background tinting not trivial without custom drawable,
                // keeping flat for simplicity
            }
            container.addView(tv)
        }

        badge("Location", hasLocationPermission())
        badge("BG Location", ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            badge("Notifications", ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        }
    }

    // -------------------------------------------------------------------------
    // Log
    // -------------------------------------------------------------------------

    private fun setupLog() {
        binding.btnClearLog.setOnClickListener {
            LogManager.clearLog()
            refreshLog()
        }
        binding.btnShareLog.setOnClickListener {
            shareFile(LogManager.logFile(), "Sensor Ranger log")
        }
    }

    private fun shareFile(file: java.io.File, subject: String) {
        try {
            if (!file.exists()) {
                Toast.makeText(this, "Nothing to share yet", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshLog() {
        val text = LogManager.getLog()
        binding.tvLog.text = if (text.isBlank()) "(no log entries yet)" else text
    }

    private fun checkForCrash() {
        if (!LogManager.hasCrash()) return
        val crash = LogManager.getLastCrash() ?: return
        AlertDialog.Builder(this)
            .setTitle("⚠️ App crashed last session")
            .setMessage(crash.take(800) + if (crash.length > 800) "\n…(truncated)" else "")
            .setPositiveButton("Share log") { _, _ -> shareFile(LogManager.crashFile(), "Sensor Ranger crash") }
            .setNeutralButton("Clear") { _, _ -> LogManager.clearCrash(); refreshLog() }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun formatTime(iso: String): String {
        return try {
            val d = isoParser.parse(iso) ?: return iso
            java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(d)
        } catch (_: Exception) { iso }
    }

    abstract class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }
}
