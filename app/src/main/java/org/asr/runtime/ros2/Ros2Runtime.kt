package org.asr.runtime.ros2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class Ros2State {
    DISABLED,
    INITIALIZING,
    ACTIVE,
    DISCONNECTED,
    ERROR
}

data class Ros2Status(
    val state: Ros2State,
    val nodeName: String? = null,
    val nodeNamespace: String? = null,
    val domainId: Int = 0
)

class Ros2Runtime(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatRunnable: Runnable? = null
    private var enabled: Boolean = prefs.getBoolean(PREF_ROS2_ENABLED, false)
    private var domainId: Int = prefs.getInt(PREF_ROS2_DOMAIN_ID, 0)
    private var runtimeReady = false
    private val _status = MutableStateFlow(
        Ros2Status(if (enabled) Ros2State.DISCONNECTED else Ros2State.DISABLED, domainId = domainId)
    )
    val status: StateFlow<Ros2Status> = _status.asStateFlow()

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        Log.i(TAG, "setEnabled current=$enabled new=$value")
        enabled = value
        prefs.edit().putBoolean(PREF_ROS2_ENABLED, value).apply()
        if (!value) {
            shutdown(disabled = true)
        } else {
            // The service owns device identity and supplies it to startIfNeeded().
            // Starting here would silently create the default /asr_android node.
            _status.value = Ros2Status(Ros2State.DISCONNECTED, domainId = domainId)
        }
    }

    fun setDomainId(value: Int) {
        domainId = value
        prefs.edit().putInt(PREF_ROS2_DOMAIN_ID, value).apply()
        _status.value = _status.value.copy(domainId = domainId)
    }

    fun startIfNeeded(nodeName: String = "asr_android", nodeNamespace: String = "/") {
        Log.i(TAG, "startIfNeeded enabled=$enabled domainId=$domainId nodeName=$nodeName ns=$nodeNamespace")
        if (!enabled) {
            Log.i(TAG, "ROS 2 disabled; not initializing the native ROS runtime")
            _status.value = Ros2Status(Ros2State.DISABLED, domainId = domainId)
            return
        }
        if (runtimeReady) {
            Log.i(TAG, "ROS 2 runtime already initialized")
            return
        }

        try {
            _status.value = Ros2Status(Ros2State.INITIALIZING, nodeName, nodeNamespace, domainId)
            if (!NativeRos2Bridge.isAvailable()) {
                Log.w(TAG, "Native ROS 2 bridge library is not packaged for this build")
                _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId)
                return
            }

            val started = NativeRos2Bridge.startNode(domainId, nodeName, nodeNamespace)
            if (!started) {
                Log.e(TAG, "Failed to start native ROS 2 node $nodeNamespace/$nodeName on domain $domainId")
                _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId)
                return
            }

            NativeRos2Bridge.setMessageListener(object : NativeRos2Bridge.MessageListener {
                override fun onStringMessage(topic: String, message: String) {
                    Log.i(TAG, "ROS 2 message received on $topic: $message")
                }
            })

            // Topics are now relative to the node namespace if they don't start with /
            NativeRos2Bridge.subscribeString("test/in")

            runtimeReady = true
            _status.value = Ros2Status(Ros2State.ACTIVE, nodeName, nodeNamespace, domainId)
            publishStatus("ASR Android online")
            publishCapabilities()
            scheduleHeartbeat()
            Log.i(TAG, "Native ROS 2 node $nodeName in $nodeNamespace started on domain $domainId")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize native ROS 2 runtime", t)
            runtimeReady = false
            _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId)
        }
    }

    fun shutdown(disabled: Boolean = !enabled) {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        try {
            if (NativeRos2Bridge.isAvailable()) {
                NativeRos2Bridge.setMessageListener(null)
                NativeRos2Bridge.shutdown()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ROS 2 shutdown warning", t)
        } finally {
            runtimeReady = false
            _status.value = Ros2Status(
                if (disabled) Ros2State.DISABLED else Ros2State.DISCONNECTED,
                domainId = domainId
            )
        }
    }

    fun publishStatus(text: String) {
        if (!ready()) return
        try {
            NativeRos2Bridge.publishString("status", text)
        } catch (t: Throwable) {
            Log.w(TAG, "ROS 2 status publish failed", t)
        }
    }

    fun publishCapabilities() {
        if (!ready()) return
        try {
            val caps = org.asr.runtime.core.CapabilityManager(context).discover()
            val json = JSONObject(caps).toString()
            NativeRos2Bridge.publishString("capabilities", json)
            Log.i(TAG, "Published capabilities: $json")
        } catch (t: Throwable) {
            Log.w(TAG, "ROS 2 capabilities publish failed", t)
        }
    }

    fun publishImu(accel: FloatArray, gyro: FloatArray, rotation: FloatArray, timestampNs: Long) {
        if (!ready()) return
        NativeRos2Bridge.publishImu(
            "imu",
            accel.map { it.toDouble() }.toDoubleArray(),
            gyro.map { it.toDouble() }.toDoubleArray(),
            doubleArrayOf(rotation.getOrElse(0) { 0f }.toDouble(), rotation.getOrElse(1) { 0f }.toDouble(), rotation.getOrElse(2) { 0f }.toDouble(), rotation.getOrElse(3) { 1f }.toDouble()),
            timestampNs
        )
    }

    fun publishGps(latitude: Double, longitude: Double, altitude: Double, accuracy: Float, timestampNs: Long) {
        if (ready()) NativeRos2Bridge.publishNavSatFix("gps", latitude, longitude, altitude, accuracy, timestampNs)
    }

    fun publishBattery(percentage: Float, temperature: Float, voltageMv: Float, charging: Boolean, timestampNs: Long) {
        if (ready()) NativeRos2Bridge.publishBattery("battery", percentage, temperature, voltageMv, charging, timestampNs)
    }

    fun publishEnvironment(kind: Int, value: Double, timestampNs: Long) {
        if (!ready()) return
        val topic = when (kind) {
            0 -> "environment/illuminance"
            1 -> "environment/pressure"
            2 -> "environment/temperature"
            else -> "environment/relative_humidity"
        }
        NativeRos2Bridge.publishScalar(topic, value, timestampNs, kind)
    }

    fun publishCameraJpeg(data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (ready()) {
            NativeRos2Bridge.publishCompressedImage("camera/image/compressed", data, "jpeg", timestampNs)
            NativeRos2Bridge.publishCameraInfo("camera/camera_info", width, height, timestampNs)
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
