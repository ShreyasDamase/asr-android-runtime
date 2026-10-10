package org.asr.runtime.ros2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

enum class Ros2State {
    DISABLED,
    INITIALIZING,
    DDS_INITIALIZING,
    NODE_READY,
    WAITING_FOR_DISCOVERY,
    WAITING_FOR_INTERFACE,
    ACTIVE,
    DISCONNECTED,
    ERROR
}

data class Ros2Status(
    val state: Ros2State,
    val nodeName: String? = null,
    val nodeNamespace: String? = null,
    val domainId: Int = 0,
    val pathSummary: String = ""
)

class Ros2Runtime(private val context: Context) {

    interface Listener {
        fun onFlashlightCommand(on: Boolean)
        fun onSpeakerCommand(text: String)
        fun onDdsInterfacesChanged()
    }

    var listener: Listener? = null

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatRunnable: Runnable? = null
    private var discoveryPollingRunnable: Runnable? = null
    private var interfaceMonitorRunnable: Runnable? = null
    @Volatile private var discoveryStartedAtMs: Long = 0L
    @Volatile private var lastDiscoveryLogAtMs: Long = 0L
    @Volatile private var enabled: Boolean = prefs.getBoolean(PREF_ROS2_ENABLED, false)
    @Volatile private var domainId: Int = prefs.getInt(PREF_ROS2_DOMAIN_ID, 0)
    @Volatile private var runtimeReady = false
    @Volatile private var wifiPathEnabled: Boolean = prefs.getBoolean(PREF_DDS_WIFI_ENABLED, prefs.getBoolean(PREF_ROS2_ENABLED, false))
    @Volatile private var usbPathEnabled: Boolean = prefs.getBoolean(PREF_DDS_USB_ENABLED, false)
    private var lastNodeName: String? = null
    private var lastNodeNamespace: String? = null
    @Volatile private var lastSelectedInterfaceSnapshot: List<String>? = null
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

    fun setTransportPaths(wifiEnabled: Boolean, usbEnabled: Boolean) {
        wifiPathEnabled = wifiEnabled
        usbPathEnabled = usbEnabled
        _status.value = _status.value.copy(pathSummary = describePaths(wifiEnabled, usbEnabled, emptyList()))
    }

    fun startIfNeeded(nodeName: String, nodeNamespace: String) {
        Log.i(TAG, "startIfNeeded enabled=$enabled domainId=$domainId nodeName=$nodeName ns=$nodeNamespace")

        val nameChanged = lastNodeName != null && lastNodeName != nodeName
        val nsChanged = lastNodeNamespace != null && lastNodeNamespace != nodeNamespace

        lastNodeName = nodeName
        lastNodeNamespace = nodeNamespace

        if (!enabled) {
            Log.i(TAG, "ROS 2 disabled; not initializing the native ROS runtime")
            _status.value = Ros2Status(Ros2State.DISABLED, domainId = domainId)
            return
        }

        if (runtimeReady && (nameChanged || nsChanged)) {
            Log.i(TAG, "ROS 2 name or namespace changed; restarting node")
            shutdown()
        }

        if (runtimeReady) {
            Log.i(TAG, "ROS 2 runtime already initialized")
            return
        }

        try {
            _status.value = Ros2Status(
                Ros2State.INITIALIZING,
                nodeName,
                nodeNamespace,
                domainId,
                describePaths(wifiPathEnabled, usbPathEnabled, emptyList())
            )
            if (!NativeRos2Bridge.isAvailable()) {
                Log.w(TAG, "Native ROS 2 bridge library is not packaged for this build")
                _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId)
                return
            }

            val selectedInterfaces = resolveSelectedInterfaces()
            val pathSummary = describePaths(wifiPathEnabled, usbPathEnabled, selectedInterfaces)
            if (selectedInterfaces.isEmpty()) {
                _status.value = Ros2Status(
                    Ros2State.WAITING_FOR_INTERFACE,
                    nodeName,
                    nodeNamespace,
                    domainId,
                    pathSummary
                )
                Log.w(TAG, "No selected DDS network interface is available: $pathSummary")
                startInterfaceMonitor()
                return
            }

            // ── Fast DDS discovery and network-interface configuration ──
            _status.value = _status.value.copy(state = Ros2State.DDS_INITIALIZING)
            val configPath = writeFastDdsConfig(selectedInterfaces)
                ?: throw IllegalStateException("Unable to write Fast DDS profile")
            if (!NativeRos2Bridge.setFastDdsConfigPath(configPath)) {
                throw IllegalStateException("Fast DDS rejected the profile path")
            }

            val started = NativeRos2Bridge.startNode(domainId, nodeName, nodeNamespace)
            if (!started) {
                Log.e(TAG, "Failed to start native ROS 2 node $nodeNamespace/$nodeName on domain $domainId")
                _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId)
                return
            }

            _status.value = _status.value.copy(state = Ros2State.NODE_READY)

            NativeRos2Bridge.setMessageListener(object : NativeRos2Bridge.MessageListener {
                override fun onStringMessage(topic: String, message: String) {
                    Log.i(TAG, "ROS 2 message received on $topic: $message")
                    when {
                        topic.contains("flashlight") -> {
                            listener?.onFlashlightCommand(message.trim().lowercase() == "true")
                        }
                        topic.contains("speaker") -> {
                            listener?.onSpeakerCommand(message)
                        }
                    }
                }
            })

            // Relative names are expanded against the node namespace by rcl.
            NativeRos2Bridge.subscribeString("test/in")
            NativeRos2Bridge.subscribeString("flashlight")
            NativeRos2Bridge.subscribeString("speaker/speech")

            runtimeReady = true
            startInterfaceMonitor()
            _status.value = Ros2Status(
                Ros2State.WAITING_FOR_DISCOVERY,
                nodeName,
                nodeNamespace,
                domainId,
                pathSummary
            )
            discoveryStartedAtMs = SystemClock.elapsedRealtime()
            lastDiscoveryLogAtMs = 0L
            
            publishStatus("ASR Android online")
            publishCapabilities()
            scheduleHeartbeat()
            startDiscoveryPolling()
            
            Log.i(TAG, "Native ROS 2 node $nodeName in $nodeNamespace started on domain $domainId")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize native ROS 2 runtime", t)
            runtimeReady = false
            _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId)
        }
    }

    private fun writeFastDdsConfig(interfaces: List<String>): String? {
        try {
            val file = File(context.cacheDir, "fastdds_asr.xml")
            // Fast DDS accepts either interface names or IPv4 addresses in its
            // UDPv4 allowlist. Use the selected interface's concrete address
            // so the transport binds the intended tether/Wi-Fi path reliably
            // on Android devices whose native interface lookup differs from
            // Java's NetworkInterface naming.
            val allowlistEntries = interfaces.flatMap { name ->
                val ipv4Addresses = runCatching {
                    NetworkInterface.getByName(name)?.interfaceAddresses.orEmpty()
                        .mapNotNull { (it.address as? java.net.Inet4Address)?.hostAddress }
                }.getOrDefault(emptyList())
                ipv4Addresses.ifEmpty { listOf(name) }
            }.distinct()
            val interfaceRows = allowlistEntries.joinToString("\n") { address ->
                "                        <interface name=\"${xmlEscape(address)}\" netmask_filter=\"AUTO\"/>"
            }
            val discoveryServer = prefs.getString(PREF_DDS_DISCOVERY_SERVER, "").orEmpty()
            val discoveryServerRows = if (discoveryServer.isEmpty()) {
                ""
            } else {
                val separator = discoveryServer.lastIndexOf(':')
                require(separator > 0 && separator < discoveryServer.lastIndex) {
                    "Discovery Server must use IPv4:port format"
                }
                val address = discoveryServer.substring(0, separator)
                val port = discoveryServer.substring(separator + 1).toIntOrNull()
                require(address.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) &&
                    address.split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }) {
                    "Discovery Server address must be an IPv4 address"
                }
                require(port != null && port in 1..65535) {
                    "Discovery Server port must be between 1 and 65535"
                }
                """
                                    <discoveryProtocol>CLIENT</discoveryProtocol>
                                    <discoveryServersList>
                                        <locator>
                                            <udpv4>
                                                <address>${xmlEscape(address)}</address>
                                                <port>$port</port>
                                            </udpv4>
                                        </locator>
                                    </discoveryServersList>
                """.trimIndent()
            }
            val xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <dds xmlns="http://www.eprosima.com">
                    <profiles>
                        <transport_descriptors>
                            <transport_descriptor>
                                <transport_id>asr_selected_udp</transport_id>
                                <type>UDPv4</type>
                                <interfaces>
                                    <allowlist>
                $interfaceRows
                                    </allowlist>
                                </interfaces>
                            </transport_descriptor>
                        </transport_descriptors>
                        <participant profile_name="asr_participant_profile" is_default_profile="true">
                            <rtps>
                                <builtin>
                                    <discovery_config>
                                        <initialAnnouncements>
                                            <count>5</count>
                                            <period><sec>0</sec><nanosec>200000000</nanosec></period>
                                        </initialAnnouncements>
                $discoveryServerRows
                                            <leaseDuration><sec>10</sec></leaseDuration>
                                            <leaseAnnouncement><sec>2</sec></leaseAnnouncement>
                                        </discovery_config>
                                </builtin>
                                <userTransports>
                                    <transport_id>asr_selected_udp</transport_id>
                                </userTransports>
                                <useBuiltinTransports>false</useBuiltinTransports>
                            </rtps>
                        </participant>
                    </profiles>
                </dds>
            """.trimIndent().trimStart()
            file.writeText(xml)
            Log.i(TAG, "Fast DDS UDPv4 allowlist for $interfaces: $allowlistEntries")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write Fast DDS config", e)
            return null
        }
    }

    private fun resolveSelectedInterfaces(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { networkInterface ->
                val name = networkInterface.name.lowercase()
                val isWifi = name.startsWith("wlan") || name.startsWith("wifi")
                val isUsb = listOf("rndis", "usb", "ncm", "ecm", "eth", "enx")
                    .any { prefix -> name.startsWith(prefix) }
                if ((wifiPathEnabled && isWifi) || (usbPathEnabled && isUsb)) networkInterface.name else null
            }
            .distinct()
            .sorted()
    }.onFailure { Log.w(TAG, "Unable to enumerate DDS network interfaces", it) }
        .getOrDefault(emptyList())

    /**
     * USB tethering may appear after DDS starts, or retain its interface name
     * while its address changes. Recreate the participant when a selected
     * network path changes so Fast DDS refreshes its UDP locators.
     */
    private fun startInterfaceMonitor() {
        stopInterfaceMonitor()
        if (!enabled || (!wifiPathEnabled && !usbPathEnabled)) return

        lastSelectedInterfaceSnapshot = selectedInterfaceSnapshot()
        val monitor = object : Runnable {
            override fun run() {
                if (!enabled || (!wifiPathEnabled && !usbPathEnabled)) {
                    stopInterfaceMonitor()
                    return
                }

                val current = selectedInterfaceSnapshot()
                val previous = lastSelectedInterfaceSnapshot
                if (previous != null && current != previous) {
                    lastSelectedInterfaceSnapshot = current
                    Log.i(TAG, "Selected DDS interfaces changed: $previous -> $current")
                    listener?.onDdsInterfacesChanged()
                    return
                }

                mainHandler.postDelayed(this, INTERFACE_POLL_INTERVAL_MS)
            }
        }
        interfaceMonitorRunnable = monitor
        mainHandler.postDelayed(monitor, INTERFACE_POLL_INTERVAL_MS)
    }

    private fun stopInterfaceMonitor() {
        interfaceMonitorRunnable?.let(mainHandler::removeCallbacks)
        interfaceMonitorRunnable = null
        lastSelectedInterfaceSnapshot = null
    }

    private fun selectedInterfaceSnapshot(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { networkInterface ->
                val name = networkInterface.name.lowercase()
                val isWifi = name.startsWith("wlan") || name.startsWith("wifi")
                val isUsb = listOf("rndis", "usb", "ncm", "ecm", "eth", "enx")
                    .any { prefix -> name.startsWith(prefix) }
                if ((wifiPathEnabled && isWifi) || (usbPathEnabled && isUsb)) {
                    val addresses = networkInterface.interfaceAddresses
                        .map { it.address.hostAddress.orEmpty().substringBefore('%') }
                        .sorted()
                    "${networkInterface.name}:${addresses.joinToString(",")}"
                } else null
            }
            .sorted()
    }.onFailure { Log.w(TAG, "Unable to refresh selected DDS interfaces", it) }
        .getOrDefault(emptyList())

    private fun describePaths(wifi: Boolean, usb: Boolean, interfaces: List<String>): String {
        val selected = buildList {
            if (wifi) add("Wi-Fi")
            if (usb) add("USB")
        }.ifEmpty { listOf("none") }
        val hasWifi = interfaces.any { it.startsWith("wlan", true) || it.startsWith("wifi", true) }
        val hasUsb = interfaces.any {
            listOf("rndis", "usb", "ncm", "ecm", "eth", "enx")
                .any { prefix -> it.startsWith(prefix, true) }
        }
        val missing = buildList {
            if (wifi && !hasWifi) add("Wi-Fi link not found")
            if (usb && !hasUsb) add("USB link not found; enable USB tethering")
        }
        return buildList {
            add("Selected: ${selected.joinToString(" + ")}")
            if (interfaces.isNotEmpty()) add("interfaces: ${interfaces.joinToString()}")
            addAll(missing)
        }.joinToString(" · ")
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun startDiscoveryPolling() {
        discoveryPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryPollingRunnable = object : Runnable {
            override fun run() {
                if (!runtimeReady) return
                val nodeCount = NativeRos2Bridge.getDiscoveredRosNodeCount()
                val now = SystemClock.elapsedRealtime()
                val elapsedMs = (now - discoveryStartedAtMs).coerceAtLeast(0L)
                if (now - lastDiscoveryLogAtMs >= DISCOVERY_LOG_INTERVAL_MS) {
                    Log.i(TAG, "DDS graph probe: visibleRosNodes=$nodeCount elapsedMs=$elapsedMs paths=${_status.value.pathSummary}")
                    lastDiscoveryLogAtMs = now
                }
                val shouldBeActive = nodeCount > 0
                val currentState = _status.value.state
                if (shouldBeActive && currentState != Ros2State.ACTIVE) {
                    Log.i(TAG, "Remote ROS peers became visible after ${elapsedMs}ms: $nodeCount peers")
                    _status.value = _status.value.copy(state = Ros2State.ACTIVE)
                } else if (!shouldBeActive && currentState == Ros2State.ACTIVE) {
                    Log.w(TAG, "Remote ROS peers disappeared after ${elapsedMs}ms")
                    _status.value = _status.value.copy(state = Ros2State.WAITING_FOR_DISCOVERY)
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(discoveryPollingRunnable!!)
    }

    fun shutdown(disabled: Boolean = !enabled) {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        discoveryPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryPollingRunnable = null
        stopInterfaceMonitor()
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

    fun publishSteps(steps: Int) {
        if (ready()) NativeRos2Bridge.publishUInt32("motion/steps", steps)
    }

    fun publishAudio(data: ShortArray) {
        if (ready()) NativeRos2Bridge.publishInt16MultiArray("audio/raw", data)
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
        private const val DISCOVERY_LOG_INTERVAL_MS = 5_000L
        private const val INTERFACE_POLL_INTERVAL_MS = 1_000L
        private const val PREFS_NAME = "asr_prefs"
        private const val PREF_ROS2_ENABLED = "ros2_enabled"
        private const val PREF_ROS2_DOMAIN_ID = "ros2_domain_id"
        private const val PREF_DDS_DISCOVERY_SERVER = "dds_discovery_server"
        private const val PREF_DDS_WIFI_ENABLED = "dds_wifi_enabled"
        private const val PREF_DDS_USB_ENABLED = "dds_usb_enabled"
    }
}
