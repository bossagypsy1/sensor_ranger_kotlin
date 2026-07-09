# Sensor Ranger Kotlin — Agent Build Notes

Native Android Kotlin app that collects phone sensor data and POSTs it periodically to a configurable API endpoint. Runs as a foreground service with a persistent notification.

---

## Project layout

```
app/src/main/
  java/com/sensorranger/app/
    MainActivity.kt          — single-screen UI, ViewBinding
    SensorRangerService.kt   — foreground service, AlarmManager scheduling
    SensorCollector.kt       — SensorManager listener, builds JSON payload
    PreferencesManager.kt    — SharedPreferences wrapper for all settings
    ApiClient.kt             — OkHttp POST with 20s timeout + one retry
    LogManager.kt            — in-memory ring buffer, debounced file flush
    SensorRangerApp.kt       — Application class, global crash handler
    CrashActivity.kt         — full-screen crash viewer, built programmatically
    BootReceiver.kt          — restarts service on device boot
    PushAlarmReceiver.kt     — receives AlarmManager broadcasts to trigger pushes
  res/
    layout/activity_main.xml
    layout/status_row.xml
    layout/spinner_item.xml          — custom spinner (fixes invisible text on dark theme)
    layout/spinner_dropdown_item.xml
    mipmap-anydpi-v26/ic_launcher.xml
    drawable/ic_launcher_foreground.xml  — logo with 22dp inset (prevents icon clipping)
```

---

## Build

### CI (GitHub Actions)

Triggers automatically on every push to any branch. APK is published to GitHub Releases **only on pushes to `main`**.

The build number is passed as a Gradle project property:
```
./gradlew :app:assembleDebug -PbuildNumber=${{ github.run_number }}
```

This sets `versionCode` and `versionName` uniquely per build so Android doesn't reject installs over an existing APK with the same version code.

### Local build

Requires JDK 17 and Android SDK with `ANDROID_HOME` set.

```bash
# Generate wrapper (one-time)
gradle wrapper --gradle-version 8.6 --distribution-type bin

# Build debug APK (versionCode defaults to 1 locally)
./gradlew :app:assembleDebug

# Output
app/build/outputs/apk/debug/app-debug.apk
```

### Key Gradle facts

- `build.gradle` uses `findProperty("buildNumber")` with `=` assignment (explicit Groovy property assignment). **Do not remove the `=`** — without it Groovy parses the expression as a method call and chains on the null return value, crashing with `Value is null` during configuration.
- `System.getenv()` was tried and abandoned: the Gradle daemon can be started by an earlier CI step (e.g. `gradle wrapper`) without the env var in scope, and then reused for the actual build. Project properties (`-P`) always reach the daemon correctly.
- `versionCode` and `versionName` must use `=` for assignment in `defaultConfig {}`.

---

## Architecture

### Scheduling (important — do not revert)

Push scheduling uses **`AlarmManager.setAndAllowWhileIdle()`**, not coroutine `delay()`.

`kotlinx.coroutines.delay()` uses `Handler.postDelayed()` which Android defers in Doze mode. With a 1-hour interval the phone typically enters Doze, the delay is never delivered, and pushes stop silently. `setAndAllowWhileIdle()` fires during Doze maintenance windows.

Flow:
1. User presses Start → `SensorRangerService.startPushing()` → first push after 2s warm-up → `scheduleNextAlarm()`
2. Alarm fires → `PushAlarmReceiver.onReceive()` → starts service with `ACTION_PUSH`
3. Service `onStartCommand(ACTION_PUSH)` → push → `scheduleNextAlarm()`

### `startForeground()` timing (important — do not revert)

Android requires `startForeground()` to be called within **5 seconds** of `startForegroundService()`. It is called immediately at the top of `onStartCommand()` via `callStartForeground()` before any `when` branching or async work. A fallback retries without the location foreground type if the first call fails (e.g. location permission denied).

### Null intent / START_STICKY restart

When Android kills and restarts the service, `onStartCommand()` receives `intent = null`. The `null` branch in the `when` block checks `prefs.running` and calls `startPushing()` if true. **Do not remove this branch** — without it, START_STICKY restarts silently do nothing and the `ForegroundServiceDidNotStartInTimeException` crash occurs (GitHub issue #4).

### `isPushing` flag

Tracks whether `startPushing()` has been called on the current service instance. Used to distinguish:
- `ACTION_PUSH` with `isPushing = true` → service already running, just push + reschedule
- `ACTION_PUSH` with `isPushing = false` → service was killed and restarted by alarm, call `startPushing()` first

---

## Device ID

Fixed constant in `PreferencesManager.DEFAULT_DEVICE_ID`:
```
4df4073a-e032-4333-b542-6cca78d502d5
```

This ID is provisioned in the receiving server app. It must not change. On first launch it is persisted to SharedPreferences; after that the persisted value is used (so editing via the UI is possible). Do not replace it with `UUID.randomUUID()`.

---

## Sensor payload

POST body shape:
```json
{
  "deviceId": "4df4073a-e032-4333-b542-6cca78d502d5",
  "messageId": 42,
  "sessionId": "session-<uuid>-<timestamp>",
  "payload": [
    { "name": "accelerometer", "time": 1716489600000000000, "values": { "x": 0.01, "y": -0.02, "z": 9.8 } },
    { "name": "location",      "time": 1716489600000000000, "values": { "latitude": 53.33, "longitude": -3.06, "speed": 0.0, "bearing": 0.0 } }
  ]
}
```

- `time` is UTC nanoseconds: `System.currentTimeMillis() * 1_000_000L`
- Sensors with no data yet are silently omitted from `payload`
- Compass uses tilt-compensated bearing via `SensorManager.getRotationMatrix()` when gravity + mag are available

### Test push location

`sendTestPush()` in `MainActivity` creates its own `SensorCollector`. Location is seeded via `FusedLocationProviderClient.lastLocation` with a `CompletableDeferred` and 2s timeout before `buildPayload()` is called. Do not remove this — without it the test push silently omits GPS.

---

## LogManager

- In-memory `ArrayDeque` ring buffer (max 200 lines), all access `synchronized`
- File writes are debounced: 2s after the last `log()` call
- `getLog()` reads from memory (instant, no file I/O)
- `clearLog()` cancels any pending flush and deletes the file
- Crash log is a separate file written synchronously in `saveCrash()`
- Log entries are prefixed `MM-dd HH:mm:ss` (e.g. `07-10 14:32:05`)

---

## Known constraints / things to preserve

| Constraint | Reason |
|---|---|
| `android:stopWithTask="false"` on the service | Without this, swiping the app away from recents kills the service |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission | Required to prompt the user to exempt the app from OEM battery killers |
| Battery opt prompt shown only once | Gated by `prefs.batteryOptPromptShown`; shows on first Start Service press |
| Custom `spinner_item.xml` / `spinner_dropdown_item.xml` | Default Android spinner uses dark text invisible on dark theme |
| `ic_launcher_foreground.xml` wraps logo with 22dp inset | Without padding the icon is clipped by Android's adaptive icon mask |
| `lifecycle-runtime-ktx` dependency | Required for `lifecycleScope` in `MainActivity` |
