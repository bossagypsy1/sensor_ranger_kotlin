# Sensor Ranger (Kotlin)

Native Android Kotlin version of Sensor Ranger.

Collects phone sensor data and periodically POSTs it to a configurable API endpoint.

---

## Build APK via GitHub Actions

1. Push this folder to a GitHub repository
2. Go to **Actions** → **Build Android Debug APK** → **Run workflow**
3. Wait ~3–5 minutes
4. Download `app-debug.apk` from the **Releases** section — direct download, no zip

---

## Build locally

Requires: Java JDK 17, Gradle 8.6

```bash
# First time: generate the Gradle wrapper
gradle wrapper --gradle-version 8.6 --distribution-type bin

# Build
./gradlew :app:assembleDebug          # Linux/Mac
gradlew.bat :app:assembleDebug        # Windows
```

APK output:
```
app/build/outputs/apk/debug/app-debug.apk
```

Install:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

```
app/src/main/java/com/sensorranger/app/
├── MainActivity.kt          — UI, settings, controls
├── SensorRangerService.kt   — Foreground service, push loop
├── SensorCollector.kt       — SensorManager listeners, payload builder
├── ApiClient.kt             — OkHttp POST with retry
├── PreferencesManager.kt    — SharedPreferences wrapper
└── BootReceiver.kt          — Restart service on boot
```

---

## Sensors collected

| Sensor | Android API |
|---|---|
| Accelerometer | TYPE_ACCELEROMETER |
| Gyroscope | TYPE_GYROSCOPE |
| Magnetometer | TYPE_MAGNETIC_FIELD |
| Gravity | TYPE_GRAVITY |
| Orientation | TYPE_ROTATION_VECTOR (quaternion + euler) |
| Compass | Tilt-compensated from gravity + magnetometer |
| Barometer | TYPE_PRESSURE |
| Total Acceleration | TYPE_LINEAR_ACCELERATION |
| Gyroscope Uncalibrated | TYPE_GYROSCOPE_UNCALIBRATED |
| Magnetometer Uncalibrated | TYPE_MAGNETIC_FIELD_UNCALIBRATED |
| Accelerometer Uncalibrated | TYPE_ACCELEROMETER_UNCALIBRATED |
| Location | FusedLocationProviderClient |
| Battery | BatteryManager |

Unavailable sensors are skipped gracefully.

---

## Permissions required

- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+)
- `WAKE_LOCK`
- `INTERNET`
