package org.asr.runtime.ros2

import org.asr.runtime.camera.CameraIntrinsics
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.asr.runtime.BuildConfig
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
    RETRYING,
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
        fun onDdsRetryRequested()
        fun onConfigCommand(command: String)
        fun onConfigStateTick()
    }

    var listener: Listener? = null

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatRunnable: Runnable? = null
    private var discoveryPollingRunnable: Runnable? = null
    @Volatile private var cameraIntrinsics: CameraIntrinsics? = null
    @Volatile private var streamIntrinsics: DoubleArray? = null
    private var streamWidth = 0
    private var streamHeight = 0
    @Volatile private var imuMinIntervalNs = 0L
    private var lastImuTimestampNs = 0L
    // Reused because the IMU publishes hundreds of times per second from a single thread.
    private val imuAccel = DoubleArray(3)
    private val imuGyro = DoubleArray(3)
    private val imuOrientation = DoubleArray(4)
    @Volatile private var cameraFrames = 0
    @Volatile private var cameraFramesDropped = 0L
    private var cameraFpsSinceNs = SystemClock.elapsedRealtimeNanos()
    private var interfaceMonitorRunnable: Runnable? = null
    private var startRetryRunnable: Runnable? = null
    private var startRetryAttempt = 0
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

    fun isRuntimeReady(): Boolean = runtimeReady

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
        startRetryRunnable?.let(mainHandler::removeCallbacks)
        startRetryRunnable = null

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

            // Configure the selected DDS backend before the native RCL context starts.
            _status.value = _status.value.copy(state = Ros2State.DDS_INITIALIZING)
            val isCyclone = BuildConfig.ASR_DDS_BACKEND == "cyclone"
            val configPath = if (isCyclone) {
                writeCycloneDdsConfig(selectedInterfaces)
            } else {
                writeFastDdsConfig(selectedInterfaces)
            } ?: throw IllegalStateException("Unable to write ${BuildConfig.ASR_DDS_BACKEND} DDS profile")
            val configured = if (isCyclone) {
                NativeRos2Bridge.setCycloneDdsConfigPath(configPath)
            } else {
                NativeRos2Bridge.setFastDdsConfigPath(configPath)
            }
            if (!configured) {
                throw IllegalStateException("${BuildConfig.ASR_DDS_BACKEND} DDS rejected the profile path")
            }

            if (!configureRosSecurity()) {
                Log.e(TAG, "ROS_SECURITY_ENABLE is on, but keys for $SECURITY_ENCLAVE are missing; node not started")
                _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId, "SROS2 keys missing")
                return
            }
            RosClock.sync()
            val started = NativeRos2Bridge.startNode(domainId, nodeName, nodeNamespace)
            if (!started) {
                Log.e(TAG, "Failed to start native ROS 2 node $nodeNamespace/$nodeName on domain $domainId")
                cleanupFailedNativeStart()
                scheduleStartRetry(nodeName, nodeNamespace, "native node initialization returned false")
                return
            }

            startRetryAttempt = 0
            _status.value = _status.value.copy(state = Ros2State.NODE_READY)

            NativeRos2Bridge.setMessageListener(object : NativeRos2Bridge.MessageListener {
                override fun onStringMessage(topic: String, message: String) {
                    Log.i(TAG, "ROS 2 message received on $topic")
                    when {
                        topic.contains("config/set") -> listener?.onConfigCommand(message)
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
            NativeRos2Bridge.subscribeString("config/set")

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
            cleanupFailedNativeStart()
            scheduleStartRetry(nodeName, nodeNamespace, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun cleanupFailedNativeStart() {
        runCatching {
            if (NativeRos2Bridge.isAvailable()) NativeRos2Bridge.shutdown()
        }.onFailure { Log.w(TAG, "Cleanup after failed ROS 2 startup failed", it) }
    }

    private fun scheduleStartRetry(nodeName: String, nodeNamespace: String, reason: String) {
        if (!enabled || (!wifiPathEnabled && !usbPathEnabled)) {
            _status.value = Ros2Status(Ros2State.ERROR, nodeName, nodeNamespace, domainId)
            return
        }

        val delayMs = (START_RETRY_BASE_MS shl startRetryAttempt.coerceAtMost(5))
            .coerceAtMost(START_RETRY_MAX_MS)
        startRetryAttempt = (startRetryAttempt + 1).coerceAtMost(6)
        _status.value = Ros2Status(
            Ros2State.RETRYING,
            nodeName,
            nodeNamespace,
            domainId,
            "Retrying DDS startup in ${delayMs / 1000}s"
        )
        Log.w(TAG, "ROS 2 startup failed ($reason); retrying in ${delayMs}ms (attempt $startRetryAttempt)")

        val retry = Runnable {
            startRetryRunnable = null
            if (enabled && (wifiPathEnabled || usbPathEnabled)) {
                listener?.onDdsRetryRequested()
            }
        }
        startRetryRunnable = retry
        mainHandler.postDelayed(retry, delayMs)
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

    private fun writeCycloneDdsConfig(interfaces: List<String>): String? = runCatching {
        val addresses = interfaces.flatMap { name ->
            NetworkInterface.getByName(name)?.interfaceAddresses.orEmpty()
                .mapNotNull { (it.address as? java.net.Inet4Address)?.hostAddress }
                .map { it.substringBefore('%') }
        }.distinct().sorted()
        require(addresses.isNotEmpty()) { "Selected DDS interfaces have no IPv4 addresses: $interfaces" }
        val interfaceRows = addresses.joinToString("\n") { address ->
            "                <NetworkInterface address=\"${xmlEscape(address)}\"/>"
        }
        val peers = prefs.getString(PREF_STATIC_PEERS, "").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // Static peers reach networks that block multicast, the same idea as ROS_STATIC_PEERS.
        val discoveryRows = if (peers.isEmpty()) "" else """
                    <Discovery>
                      <Peers>
${peers.joinToString("\n") { "                        <Peer address=\"${xmlEscape(it)}\"/>" }}
                      </Peers>
                      <ParticipantIndex>auto</ParticipantIndex>
                    </Discovery>"""
        // Debug builds log Cyclone errors, which rcl doesn't pass on, while SROS2 is on.
        val traceRows = if (BuildConfig.DEBUG && prefs.getBoolean(PREF_ROS_SECURITY_ENABLE, false)) """
                    <Tracing>
                      <Verbosity>warning</Verbosity>
                      <OutputFile>${xmlEscape(File(context.cacheDir, "cyclonedds_trace.log").absolutePath)}</OutputFile>
                    </Tracing>""" else ""
        val file = File(context.cacheDir, "cyclonedds_asr.xml")
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
                <CycloneDDS xmlns="https://cdds.io/config">
                  <Domain Id="$domainId">
                    <General>
                      <Interfaces>
            $interfaceRows
                      </Interfaces>
                      <AllowMulticast>default</AllowMulticast>
                    </General>
                    <Internal>
                      <SocketSendBufferSize min="64KiB" max="128KiB"/>
                    </Internal>$discoveryRows
$traceRows
                  </Domain>
                </CycloneDDS>
            """.trimIndent()
        )
        Log.i(TAG, "Cyclone DDS interface addresses for $interfaces: $addresses")
        file.absolutePath
    }.onFailure { Log.e(TAG, "Failed to write Cyclone DDS config", it) }.getOrNull()

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
        startRetryRunnable?.let(mainHandler::removeCallbacks)
        startRetryRunnable = null
        if (disabled) startRetryAttempt = 0
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

    /**
     * Publishes sensor frames on /tf_static. Rotations follow Android's device axes (x right, y up,
     * z out of the screen) and each camera's usual mounting. Translations are zero because phones
     * don't report lens offsets, so add your own base_link transform for precise work.
     */
    fun publishStaticTransforms(frontCamera: Boolean) {
        if (!ready()) return
        val s = 0.7071067811865476
        val camera = if (frontCamera) {
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, -s, s)
        } else {
            doubleArrayOf(0.0, 0.0, 0.0, s, -s, 0.0, 0.0)
        }
        val identity = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        val ok = NativeRos2Bridge.publishStaticTransforms(
            "base_link",
            arrayOf("imu_link", "camera_optical_frame", "gps_link", "battery_link", "environment_link"),
            identity + camera + identity + identity + identity,
            RosClock.toUnixNs(SystemClock.elapsedRealtimeNanos())
        )
        if (!ok) Log.w(TAG, "tf_static publish failed")
    }

    /** Publishes the retained config/state topic. */
    fun publishConfigState(json: String) {
        if (!ready()) return
        try {
            NativeRos2Bridge.publishString("config/state", json)
        } catch (t: Throwable) {
            Log.w(TAG, "ROS 2 config state publish failed", t)
        }
    }

    /** Limits IMU messages to this rate. 0 publishes every sample. */
    fun setImuRateLimitHz(rateHz: Int) {
        imuMinIntervalNs = if (rateHz > 0) 1_000_000_000L / rateHz else 0L
    }

    /** Camera frames per second published since the previous call. */
    fun takeCameraFps(): Double {
        val now = SystemClock.elapsedRealtimeNanos()
        val frames = cameraFrames
        val elapsedNs = now - cameraFpsSinceNs
        cameraFrames = 0
        cameraFpsSinceNs = now
        return if (elapsedNs > 0) frames * 1e9 / elapsedNs else 0.0
    }

    /** Camera frames dropped as stale since the node started. */
    fun cameraFramesDropped(): Long = cameraFramesDropped

    fun cameraStreamSize(): String = if (streamWidth > 0) "${streamWidth}x${streamHeight}" else ""

    fun publishImu(accel: FloatArray, gyro: FloatArray, rotation: FloatArray, timestampNs: Long) {
        if (!ready()) return
        val minInterval = imuMinIntervalNs
        if (minInterval > 0L) {
            if (timestampNs - lastImuTimestampNs < minInterval) return
            lastImuTimestampNs = timestampNs
        }
        for (i in 0 until 3) {
            imuAccel[i] = accel[i].toDouble()
            imuGyro[i] = gyro[i].toDouble()
        }
        for (i in 0 until 4) imuOrientation[i] = rotation[i].toDouble()
        NativeRos2Bridge.publishImu(
            "imu",
            imuAccel,
            imuGyro,
            imuOrientation,
            RosClock.toUnixNs(timestampNs)
        )
    }

    fun publishGps(latitude: Double, longitude: Double, altitude: Double, accuracy: Float, timestampNs: Long) {
        if (ready()) NativeRos2Bridge.publishNavSatFix("gps", latitude, longitude, altitude, accuracy, RosClock.toUnixNs(timestampNs))
    }

    fun publishSteps(steps: Int) {
        if (ready()) NativeRos2Bridge.publishUInt32("motion/steps", steps)
    }

    fun publishAudio(data: ShortArray) {
        if (ready()) NativeRos2Bridge.publishInt16MultiArray("audio/raw", data)
    }

    fun publishBattery(percentage: Float, temperature: Float, voltageMv: Float, charging: Boolean, timestampNs: Long) {
        if (ready()) NativeRos2Bridge.publishBattery("battery", percentage, temperature, voltageMv, charging, RosClock.toUnixNs(timestampNs))
    }

    fun publishEnvironment(kind: Int, value: Double, timestampNs: Long) {
        if (!ready()) return
        val topic = when (kind) {
            0 -> "environment/illuminance"
            1 -> "environment/pressure"
            2 -> "environment/temperature"
            else -> "environment/relative_humidity"
        }
        NativeRos2Bridge.publishScalar(topic, value, RosClock.toUnixNs(timestampNs), kind)
    }

    private fun keystoreDir() = File(context.filesDir, "ros2_keystore")

    /** True when this phone holds the certificates and key for its enclave. */
    fun hasSecurityKeys(): Boolean {
        importSecurityKeys()
        val enclave = File(keystoreDir(), "enclaves$SECURITY_ENCLAVE")
        return SECURITY_FILES.all { File(enclave, it).isFile }
    }

    /**
     * Moves keys pushed to the app's external folder into private storage, so the private key
     * doesn't stay where other tools can read it.
     */
    private fun importSecurityKeys() {
        val pushed = context.getExternalFilesDir(null)?.let { File(it, "ros2_keystore/enclaves$SECURITY_ENCLAVE") } ?: return
        if (!pushed.isDirectory) return
        val target = File(keystoreDir(), "enclaves$SECURITY_ENCLAVE")
        target.mkdirs()
        pushed.listFiles()?.filter { it.isFile }?.forEach { file ->
            file.copyTo(File(target, file.name), overwrite = true)
            file.delete()
        }
        Log.i(TAG, "Imported SROS2 keys for $SECURITY_ENCLAVE")
    }

    /** Returns false when security is on but can't be used. It never falls back to no security. */
    private fun configureRosSecurity(): Boolean {
        if (!prefs.getBoolean(PREF_ROS_SECURITY_ENABLE, false)) {
            NativeRos2Bridge.setRosSecurity(null, null)
            return true
        }
        if (BuildConfig.ASR_DDS_BACKEND != "cyclone" || !hasSecurityKeys()) return false
        // Cyclone looks these up by name, so load them first where Android can resolve them.
        val loaded = runCatching {
            listOf("dds_security_auth", "dds_security_ac", "dds_security_crypto").forEach(System::loadLibrary)
        }
        if (loaded.isFailure) {
            Log.e(TAG, "Unable to load SROS2 plugins", loaded.exceptionOrNull())
            return false
        }
        NativeRos2Bridge.setRosSecurity(keystoreDir().absolutePath, SECURITY_ENCLAVE)
        return true
    }

    /** Set when the active camera reports lens values; null publishes the camera as uncalibrated. */
    fun setCameraIntrinsics(intrinsics: CameraIntrinsics?) {
        cameraIntrinsics = intrinsics
        streamIntrinsics = null
    }

    fun publishCameraJpeg(data: ByteArray, width: Int, height: Int, timestampNs: Long) {
        if (ready()) {
            val stampNs = RosClock.toUnixNs(timestampNs)
            // A robot needs the newest frame, not an old one delivered late. Drop stale frames.
            val ageNs = System.currentTimeMillis() * 1_000_000L - stampNs
            if (ageNs > MAX_CAMERA_FRAME_AGE_NS) {
                cameraFramesDropped++
                return
            }
            cameraFrames++
            NativeRos2Bridge.publishCompressedImage("camera/image/compressed", data, "jpeg", stampNs)
            val lens = cameraIntrinsics
            var stream = streamIntrinsics
            if (stream == null || streamWidth != width || streamHeight != height) {
                stream = lens?.forStream(width, height) ?: DoubleArray(4)
                streamIntrinsics = stream
                streamWidth = width
                streamHeight = height
            }
            NativeRos2Bridge.publishCameraInfo(
                "camera/camera_info", width, height, stream, lens?.distortion ?: NO_DISTORTION, stampNs
            )
        }
    }

    private fun ready(): Boolean = enabled && runtimeReady && NativeRos2Bridge.isAvailable()

    private fun scheduleHeartbeat() {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (enabled && runtimeReady) {
                    publishStatus("ASR heartbeat")
                    listener?.onConfigStateTick()
                }
                mainHandler.postDelayed(this, 5000)
            }
        }
        mainHandler.post(heartbeatRunnable!!)
    }

    companion object {
        private const val TAG = "Ros2Runtime"
        private const val MAX_CAMERA_FRAME_AGE_NS = 250_000_000L
        private val NO_DISTORTION = DoubleArray(5)
        private const val START_RETRY_BASE_MS = 1_000L
        private const val START_RETRY_MAX_MS = 30_000L
        private const val DISCOVERY_LOG_INTERVAL_MS = 5_000L
        private const val INTERFACE_POLL_INTERVAL_MS = 1_000L
        private const val PREFS_NAME = "asr_prefs"
        private const val PREF_ROS2_ENABLED = "ros2_enabled"
        private const val PREF_ROS2_DOMAIN_ID = "ros2_domain_id"
        private const val PREF_DDS_DISCOVERY_SERVER = "dds_discovery_server"
        private const val PREF_STATIC_PEERS = "ros_static_peers"
        private const val PREF_ROS_SECURITY_ENABLE = "ros_security_enable"
        private const val SECURITY_ENCLAVE = "/asr_android"
        private val SECURITY_FILES = listOf(
            "identity_ca.cert.pem", "cert.pem", "key.pem",
            "permissions_ca.cert.pem", "governance.p7s", "permissions.p7s"
        )
        private const val PREF_DDS_WIFI_ENABLED = "dds_wifi_enabled"
        private const val PREF_DDS_USB_ENABLED = "dds_usb_enabled"
    }
}
