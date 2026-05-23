package com.sensorranger.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject

class SensorCollector(private val context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Latest cached values
    @Volatile var latestAccel: FloatArray? = null
    @Volatile var latestGyro: FloatArray? = null
    @Volatile var latestMag: FloatArray? = null
    @Volatile var latestGravity: FloatArray? = null
    @Volatile var latestRotationVec: FloatArray? = null
    @Volatile var latestPressure: Float? = null
    @Volatile var latestLinearAccel: FloatArray? = null
    @Volatile var latestGyroUncal: FloatArray? = null
    @Volatile var latestMagUncal: FloatArray? = null
    @Volatile var latestAccelUncal: FloatArray? = null
    @Volatile var latestLocation: Location? = null

    fun start() {
        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_MAGNETIC_FIELD)
        register(Sensor.TYPE_GRAVITY)
        register(Sensor.TYPE_ROTATION_VECTOR)
        register(Sensor.TYPE_PRESSURE)
        register(Sensor.TYPE_LINEAR_ACCELERATION)
        register(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        register(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        register(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        latestAccel = null; latestGyro = null; latestMag = null
        latestGravity = null; latestRotationVec = null; latestPressure = null
        latestLinearAccel = null; latestGyroUncal = null; latestMagUncal = null
        latestAccelUncal = null; latestLocation = null
    }

    private fun register(type: Int) {
        val sensor = sensorManager.getDefaultSensor(type) ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> latestAccel = event.values.clone()
            Sensor.TYPE_GYROSCOPE -> latestGyro = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> latestMag = event.values.clone()
            Sensor.TYPE_GRAVITY -> latestGravity = event.values.clone()
            Sensor.TYPE_ROTATION_VECTOR -> latestRotationVec = event.values.clone()
            Sensor.TYPE_PRESSURE -> latestPressure = event.values[0]
            Sensor.TYPE_LINEAR_ACCELERATION -> latestLinearAccel = event.values.clone()
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> latestGyroUncal = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> latestMagUncal = event.values.clone()
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> latestAccelUncal = event.values.clone()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    fun buildPayload(prefs: PreferencesManager): JSONArray {
        val arr = JSONArray()
        val now = System.currentTimeMillis() * 1_000_000L

        if (prefs.isSensorEnabled("location")) {
            latestLocation?.let { loc ->
                arr.put(sensorEntry("location", now, JSONObject().apply {
                    put("latitude", loc.latitude)
                    put("longitude", loc.longitude)
                    put("speed", if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
                    put("bearing", if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
                }))
            }
        }

        if (prefs.isSensorEnabled("battery")) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level >= 0) {
                arr.put(sensorEntry("battery", now, JSONObject().apply {
                    put("batteryLevel", level / 100.0)
                }))
            }
        }

        if (prefs.isSensorEnabled("accelerometer")) {
            latestAccel?.let { v ->
                arr.put(sensorEntry("accelerometer", now, xyz(v)))
            }
        }

        if (prefs.isSensorEnabled("totalAcceleration")) {
            // Use linear acceleration (excludes gravity) if available, else fall back to accel
            val v = latestLinearAccel ?: latestAccel
            v?.let { arr.put(sensorEntry("totalAcceleration", now, xyz(it))) }
        }

        if (prefs.isSensorEnabled("accelerometerUncalibrated")) {
            latestAccelUncal?.let { v ->
                arr.put(sensorEntry("accelerometerUncalibrated", now, JSONObject().apply {
                    put("x", v[0].toDouble())
                    put("y", v[1].toDouble())
                    put("z", v[2].toDouble())
                }))
            }
        }

        if (prefs.isSensorEnabled("gravity")) {
            latestGravity?.let { v ->
                arr.put(sensorEntry("gravity", now, xyz(v)))
            }
        }

        if (prefs.isSensorEnabled("gyroscope")) {
            latestGyro?.let { v ->
                arr.put(sensorEntry("gyroscope", now, xyz(v)))
            }
        }

        if (prefs.isSensorEnabled("gyroscopeUncalibrated")) {
            latestGyroUncal?.let { v ->
                arr.put(sensorEntry("gyroscopeUncalibrated", now, JSONObject().apply {
                    put("x", v[0].toDouble())
                    put("y", v[1].toDouble())
                    put("z", v[2].toDouble())
                }))
            }
        }

        if (prefs.isSensorEnabled("orientation")) {
            latestRotationVec?.let { rv ->
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, rv)
                // q = [w, x, y, z]
                val rotMat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMat, rv)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotMat, orientation)
                arr.put(sensorEntry("orientation", now, JSONObject().apply {
                    put("qw", q[0].toDouble())
                    put("qx", q[1].toDouble())
                    put("qy", q[2].toDouble())
                    put("qz", q[3].toDouble())
                    put("yaw", Math.toDegrees(orientation[0].toDouble()))
                    put("pitch", Math.toDegrees(orientation[1].toDouble()))
                    put("roll", Math.toDegrees(orientation[2].toDouble()))
                }))
            }
        }

        if (prefs.isSensorEnabled("magnetometer")) {
            latestMag?.let { v ->
                arr.put(sensorEntry("magnetometer", now, xyz(v)))
            }
        }

        if (prefs.isSensorEnabled("magnetometerUncalibrated")) {
            latestMagUncal?.let { v ->
                arr.put(sensorEntry("magnetometerUncalibrated", now, JSONObject().apply {
                    put("x", v[0].toDouble())
                    put("y", v[1].toDouble())
                    put("z", v[2].toDouble())
                }))
            }
        }

        if (prefs.isSensorEnabled("compass")) {
            // Use rotation vector + orientation for accurate compass if available
            val bearing = computeCompassBearing()
            if (bearing != null) {
                arr.put(sensorEntry("compass", now, JSONObject().apply {
                    put("magneticBearing", bearing)
                }))
            }
        }

        if (prefs.isSensorEnabled("barometer")) {
            latestPressure?.let { p ->
                arr.put(sensorEntry("barometer", now, JSONObject().apply {
                    put("pressure", p.toDouble())
                    put("relativeAltitude", 0.0)
                }))
            }
        }

        return arr
    }

    private fun computeCompassBearing(): Double? {
        // Prefer gravity + mag for tilt-compensated bearing
        val grav = latestGravity ?: latestAccel ?: return rawMagBearing()
        val mag = latestMag ?: return null
        val R = FloatArray(9)
        val I = FloatArray(9)
        if (!SensorManager.getRotationMatrix(R, I, grav, mag)) return rawMagBearing()
        val orientation = FloatArray(3)
        SensorManager.getOrientation(R, orientation)
        var azimuth = Math.toDegrees(orientation[0].toDouble())
        if (azimuth < 0) azimuth += 360.0
        return azimuth
    }

    private fun rawMagBearing(): Double? {
        val mag = latestMag ?: return null
        var bearing = Math.toDegrees(Math.atan2(mag[1].toDouble(), mag[0].toDouble()))
        if (bearing < 0) bearing += 360.0
        return bearing
    }

    private fun xyz(v: FloatArray) = JSONObject().apply {
        put("x", v[0].toDouble())
        put("y", v[1].toDouble())
        put("z", v[2].toDouble())
    }

    private fun sensorEntry(name: String, time: Long, values: JSONObject) =
        JSONObject().apply {
            put("name", name)
            put("time", time)
            put("values", values)
        }
}
