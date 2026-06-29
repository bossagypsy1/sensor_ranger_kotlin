# Kotlin/Android Developer Guide — Sensor Ranger

## Project structure

```
app/src/main/java/com/sensorranger/app/
├── MainActivity.kt          — UI (ViewBinding), permission flow, service controls
├── SensorRangerService.kt   — Foreground service, AlarmManager push loop
├── SensorCollector.kt       — SensorEventListener, payload builder
├── ApiClient.kt             — OkHttp3 POST with single retry (object singleton)
├── PreferencesManager.kt    — SharedPreferences wrapper (typed Kotlin properties)
├── LogManager.kt            — In-app log + crash file (object singleton)
├── BootReceiver.kt          — BOOT_COMPLETED → restart service if was running
├── PushAlarmReceiver.kt     — AlarmManager fires ACTION_PUSH intent to service
├── SensorRangerApp.kt       — Application subclass (LogManager.init)
└── CrashActivity.kt         — Fallback crash display
```

---

## Key patterns

### Foreground service lifecycle

`SensorRangerService` must call `startForeground()` within 5 seconds of
`startForegroundService()` or Android kills it with an ANR. The call happens in
`onStartCommand` **before any async work**, for every non-STOP action including
the null/restart case.

```kotlin
// Always call this first, before any coroutine launch
if (intent?.action != ACTION_STOP) {
    callStartForeground()
}
```

`callStartForeground()` tries `FOREGROUND_SERVICE_TYPE_LOCATION` first; if the
location permission is denied at runtime it falls back to typeless
`startForeground()`; if that also fails it calls `stopSelf()`.

### AlarmManager push scheduling

Pushes are driven by `AlarmManager.setAndAllowWhileIdle()` rather than a
coroutine loop or `WorkManager`. This survives Doze mode without requiring
`SCHEDULE_EXACT_ALARM` (introduced as a restricted permission on API 31+).

```kotlin
am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
```

`PushAlarmReceiver` receives the broadcast and forwards `ACTION_PUSH` to the
service. On API 31+ use `PendingIntent.FLAG_IMMUTABLE`.

### Service restart on kill

`onStartCommand` returns `START_STICKY`. When Android restarts a killed service
the intent is null — the service checks `prefs.running` and resumes or
self-stops.

```kotlin
null -> {
    if (prefs.running) startPushing() else stopSelf()
}
```

If the alarm also fires while the service is dead, `ACTION_PUSH` arrives with
`isPushing == false` — the service calls `startPushing()` to re-initialise.

---

## Sensor data

`SensorCollector` implements `SensorEventListener`. All latest values are
`@Volatile` fields; they are read on the IO coroutine during `buildPayload()`.

Sensors are registered with `SENSOR_DELAY_NORMAL` (~200 ms). Missing sensors
are skipped silently:

```kotlin
private fun register(type: Int) {
    val sensor = sensorManager.getDefaultSensor(type) ?: return
    sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
}
```

Compass bearing is tilt-compensated using gravity + magnetometer via
`SensorManager.getRotationMatrix()`. Falls back to raw atan2 on the mag vector
when gravity is unavailable.

Orientation is derived from `TYPE_ROTATION_VECTOR` — provides quaternion (qw,
qx, qy, qz) and Euler angles (yaw, pitch, roll in degrees).

---

## Payload format

`SensorCollector.buildPayload()` returns a `JSONArray`. Each entry:

```json
{
  "name": "accelerometer",
  "time": 1700000000000000000,
  "values": { "x": 0.1, "y": 9.8, "z": 0.2 }
}
```

`time` is nanoseconds (`System.currentTimeMillis() * 1_000_000L`).

The outer POST body sent by `SensorRangerService.performPush()`:

```json
{
  "deviceId":  "4df4073a-...",
  "messageId": 42,
  "sessionId": "session-<uuid>-<timestamp>",
  "payload":   [ ... ]
}
```

`deviceId` defaults to the hardcoded constant in `PreferencesManager` —
change `DEFAULT_DEVICE_ID` before building for a different deployment.

---

## Preferences

`PreferencesManager` wraps `SharedPreferences` with Kotlin property delegates:

```kotlin
var apiUrl: String
    get() = prefs.getString("apiUrl", DEFAULT_API_URL) ?: DEFAULT_API_URL
    set(v) = prefs.edit().putString("apiUrl", v).apply()
```

Sensor toggles are stored as `sensor_<key>` booleans. The canonical key list
and default states live in `SENSOR_KEYS` (companion object).

Push frequency options are in `FREQUENCY_OPTIONS` (1 min → 1 day). The stored
value is raw milliseconds.

---

## Network

`ApiClient` is a Kotlin `object` (singleton). Uses OkHttp3 with 20 s timeouts.
`post()` is a `suspend fun` — it does one retry after a 2 s delay on failure:

```kotlin
suspend fun post(url: String, body: String, bearerToken: String?): Result {
    val first = doPost(url, body, bearerToken)
    if (first.success) return first
    delay(2000)
    return doPost(url, body, bearerToken)
}
```

`doPost` is blocking (`client.newCall(request).execute()`) and is always called
from `Dispatchers.IO`. Bearer token is optional — set via the UI toggle.

---

## Coroutines

The service owns a `CoroutineScope(Dispatchers.IO + SupervisorJob())` cancelled
in `onDestroy()`. All network calls and payload builds run on this scope.

UI coroutines in `MainActivity` use `CoroutineScope(Dispatchers.IO)` launched
inline (no retained scope) — switch to `Dispatchers.Main` with `withContext`
for UI updates.

---

## Logging

`LogManager` (object singleton) writes to `filesDir/sensor_ranger.log`, capped
at 200 lines. Also mirrors every line to logcat under tag `SensorRanger`.

```kotlin
LogManager.log("TAG", "message")
```

Crash traces are written to `filesDir/sensor_ranger_crash.log` and surfaced as
an `AlertDialog` on the next `MainActivity.onCreate()`.

To follow logs live:
```bash
adb logcat -s SensorRanger
```

---

## Permissions

| Permission | When requested |
|---|---|
| `ACCESS_FINE_LOCATION` | `MainActivity.onCreate` |
| `ACCESS_COARSE_LOCATION` | `MainActivity.onCreate` |
| `ACCESS_BACKGROUND_LOCATION` | Must be granted manually in Settings |
| `POST_NOTIFICATIONS` | `MainActivity.onCreate` (API 33+) |
| `WAKE_LOCK`, `INTERNET`, `FOREGROUND_SERVICE*` | Granted at install |

Background location is shown as a badge in the UI but not requested via the
system dialog (Android requires it to be granted separately after foreground
location is approved).

Battery optimisation exemption is prompted once when the service starts —
`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — to prevent OEM
battery killers from stopping the foreground service overnight.

---

## Adding a new sensor

1. Add a `@Volatile var latestFoo: FloatArray? = null` to `SensorCollector`.
2. Register the type in `start()` and handle it in `onSensorChanged()`.
3. Add a `SensorToggle("foo", "Foo", true)` entry to `SENSOR_KEYS` in
   `PreferencesManager`.
4. Add the `isSensorEnabled("foo")` guarded block in `buildPayload()`.

---

## Common issues

**Service killed after a few hours** — battery optimisation. Prompt the user
via `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (already done on
first start) or instruct them to enable "Unrestricted" background usage in
Settings → Apps → Sensor Ranger → Battery.

**`startForeground` crash / ForegroundServiceDidNotStartInTimeException** —
ensure `callStartForeground()` runs synchronously before any `launch {}` in
`onStartCommand`. The current code does this correctly; do not move it inside a
coroutine.

**Location is null on first push** — the 2 s warm-up delay in `startPushing()`
gives `fusedLocationClient.lastLocation` time to return. If the device has no
cached fix the first payload will omit location; subsequent pushes will include
it.

**`PendingIntent` deprecation warning on API < 31** — the code passes
`FLAG_IMMUTABLE` everywhere; this is a no-op on older APIs and correct on
API 31+.
