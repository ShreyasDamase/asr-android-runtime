package org.asr.runtime.ros2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class Ros2Runtime(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatRunnable: Runnable? = null
    private var enabled: Boolean = prefs.getBoolean(PREF_ROS2_ENABLED, false)
    private var domainId: Int = prefs.getInt(PREF_ROS2_DOMAIN_ID, 0)
    private var runtimeReady = false

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(PREF_ROS2_ENABLED, value).apply()
        if (value) {
            startIfNeeded()
        } else {
            shutdown()
        }
    }

    fun setDomainId(value: Int) {
        domainId = value
        prefs.edit().putInt(PREF_ROS2_DOMAIN_ID, value).apply()
        if (enabled && runtimeReady) {
            shutdown()
            startIfNeeded()
        }
    }

    fun startIfNeeded() {
        Log.i(TAG, "startIfNeeded enabled=$enabled domainId=$domainId")
        if (!enabled) {
            Log.i(TAG, "ROS 2 disabled; not initializing the native ROS runtime")
            return
        }
        if (runtimeReady) {
            Log.i(TAG, "ROS 2 runtime already initialized")
            return
        }

        try {
            if (!NativeRos2Bridge.isAvailable()) {
                Log.w(TAG, "Native ROS 2 bridge library is not packaged for this build; leaving the runtime disabled")
                return
            }

            val started = NativeRos2Bridge.startNode(domainId, "asr_android")
            if (!started) {
                Log.e(TAG, "Failed to start native ROS 2 node /asr_android on domain $domainId")
                shutdown()
                return
            }

            runtimeReady = true
            publishStatus("ASR Android online")
            scheduleHeartbeat()
            Log.i(TAG, "Native ROS 2 node started on domain $domainId")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize native ROS 2 runtime", t)
            shutdown()
        }
    }

    fun shutdown() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        try {
            if (NativeRos2Bridge.isAvailable()) {
                NativeRos2Bridge.shutdown()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ROS 2 shutdown warning", t)
        } finally {
            runtimeReady = false
        }
    }

    fun publishStatus(text: String) {
        if (!enabled || !runtimeReady) {
            return
        }
        try {
            if (NativeRos2Bridge.isAvailable()) {
                NativeRos2Bridge.publishString("/asr_android/status", text)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ROS 2 status publish failed", t)
        }
    }

    fun publishImu(accel: FloatArray, gyro: FloatArray, rotation: FloatArray, timestampNs: Long) {
        if (!ready()) return
        NativeRos2Bridge.publishImu(
            "/asr_android/imu",
            accel.map { it.toDouble() }.toDoubleArray(),
            gyro.map { it.toDouble() }.toDoubleArray(),
            doubleArrayOf(rotation.getOrElse(0) { 0f }.toDouble(), rotation.getOrElse(1) { 0f }.toDouble(), rotation.getOrElse(2) { 0f }.toDouble(), rotation.getOrElse(3) { 1f }.toDouble()),
            timestampNs
        )
    }

    fun publishGps(latitude: Double, longitude: Double, altitude: Double, accuracy: Float, timestampNs: Long) {
        if (ready()) NativeRos2Bridge.publishNavSatFix("/asr_android/gps", latitude, longitude, altitude, accuracy, timestampNs)
    }

    fun publishBattery(percentage: Float, temperature: Float, voltageMv: Float, charging: Boolean) {
        if (ready()) NativeRos2Bridge.publishBattery("/asr_android/battery", percentage, temperature, voltageMv, charging)
    }

    fun publishEnvironment(kind: Int, value: Double, timestampNs: Long) {
        if (!ready()) return
        val topic = when (kind) {
            0 -> "/asr_android/environment/illuminance"
            1 -> "/asr_android/environment/pressure"
            2 -> "/asr_android/environment/temperature"
            else -> "/asr_android/environment/relative_humidity"
        }
        NativeRos2Bridge.publishScalar(topic, value, timestampNs, kind)
    }

    fun publishCameraJpeg(data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (ready()) {
            NativeRos2Bridge.publishCompressedImage("/asr_android/camera/image/compressed", data, "jpeg", timestampNs)
            NativeRos2Bridge.publishCameraInfo("/asr_android/camera/camera_info", width, height, timestampNs)
        }
    }

    private fun ready(): Boolean = enabled && runtimeReady && NativeRos2Bridge.isAvailable()

    private fun scheduleHeartbeat() {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (enabled && runtimeReady) {
                    publishStatus("ASR heartbeat")
                }
                mainHandler.postDelayed(this, 5000)
            }
        }
        mainHandler.post(heartbeatRunnable!!)
    }

    companion object {
        private const val TAG = "Ros2Runtime"
        private const val PREFS_NAME = "asr_prefs"
        private const val PREF_ROS2_ENABLED = "ros2_enabled"
        private const val PREF_ROS2_DOMAIN_ID = "ros2_domain_id"
    }
}
