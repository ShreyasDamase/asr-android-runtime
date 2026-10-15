package org.asr.runtime.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import org.asr.runtime.BuildConfig
import org.asr.runtime.R
import org.asr.runtime.audio.AudioRuntime
import org.asr.runtime.audio.SpeakerManager
import org.asr.runtime.battery.BatteryManager
import org.asr.runtime.common.ModuleState
import org.asr.runtime.camera.CameraConfig
import org.asr.runtime.camera.CameraManager
import org.asr.runtime.camera.CameraPipeline
import org.asr.runtime.camera.JpegCompressor
import org.asr.runtime.diagnostic.BenchmarkProcessor
import org.asr.runtime.diagnostic.DiagnosticsManager
import org.asr.runtime.modules.gps.GpsManager
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.PacketSerializer
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.ros2.Ros2Runtime
import org.asr.runtime.ros2.Ros2Status
import org.asr.runtime.sensor.EnvironmentManager
import org.asr.runtime.sensor.ImuManager
import org.asr.runtime.sensor.MotionManager
import org.asr.runtime.transport.LogTransport
import org.asr.runtime.transport.TcpTransport
import org.asr.runtime.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground Service that owns and orchestrates the ASR hardware runtime.
 */
class AsrService : LifecycleService(), Ros2Runtime.Listener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val modeRequestLock = Any()
    private val tcpLifecycleMutex = Mutex()
    private val ros2LifecycleMutex = Mutex()
    private val pendingModeTransitions = AtomicInteger(0)

    companion object {
        private const val TAG            = "PhoneCamera"
        private const val CHANNEL_ID     = "AsrRuntimeService"
        private const val NOTIFICATION_ID = 101
        private const val PREFS_NAME     = "asr_prefs"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_NODE_ALIAS = "node_alias"
        private const val PREF_TCP_ENABLED = "tcp_enabled"
        private const val PREF_TCP_HOST = "tcp_host"
        private const val PREF_TCP_PORT = "tcp_port"
        private const val PREF_RUNTIME_ENABLED = "runtime_enabled"
        private const val PREF_ENABLED_SENSOR_MODULES = "enabled_sensor_modules"
        private const val PREF_ROS2_ENABLED = "ros2_enabled"
        private const val PREF_DDS_WIFI_ENABLED = "dds_wifi_enabled"
        private const val PREF_DDS_USB_ENABLED = "dds_usb_enabled"
        private const val PREF_DDS_DISCOVERY_SERVER = "dds_discovery_server"
        private const val TCP_TRANSPORT_TYPE = "tcp"

        // ADB broadcast actions
        const val ACTION_START          = "org.asr.runtime.START"
        const val ACTION_STOP           = "org.asr.runtime.STOP"
        const val ACTION_RESTART        = "org.asr.runtime.RESTART"
        const val ACTION_STATUS         = "org.asr.runtime.STATUS"
        const val ACTION_SPEAK          = "org.asr.runtime.SPEAK"
        const val ACTION_MODULE_START   = "org.asr.runtime.MODULE_START"
        const val ACTION_MODULE_STOP    = "org.asr.runtime.MODULE_STOP"
        const val ACTION_PROFILE_ENABLE = "org.asr.runtime.PROFILE_ENABLE"
        const val ACTION_CAMERA_CONFIG  = "org.asr.runtime.CAMERA_CONFIG"
        const val ACTION_FLASHLIGHT     = "org.asr.runtime.FLASHLIGHT"
        const val ACTION_TCP_CONNECT    = "org.asr.runtime.TCP_CONNECT"
        const val ACTION_TCP_ENABLE     = "org.asr.runtime.TCP_ENABLE"
        const val ACTION_TCP_DISABLE    = "org.asr.runtime.TCP_DISABLE"
        const val ACTION_ROS2_ENABLE    = "org.asr.runtime.ROS2_ENABLE"
        const val ACTION_ROS2_DISABLE   = "org.asr.runtime.ROS2_DISABLE"
        const val ACTION_DDS_PATHS_SET  = "org.asr.runtime.DDS_PATHS_SET"
        const val ACTION_DEV_MODE       = "org.asr.runtime.DEV_MODE"
        const val ACTION_SET_ALIAS      = "org.asr.runtime.SET_ALIAS"

        private val SENSOR_RUNTIME_MODULE_IDS = listOf(
            "camera", "imu", "environment", "motion", "audio", "battery", "speaker", "gps", "diagnostics"
        )

        /** User-selectable acquisition modules; speaker and diagnostics remain service support modules. */
        val CONFIGURABLE_SENSOR_MODULE_IDS = listOf(
            "camera", "imu", "environment", "motion", "audio", "battery", "gps"
        )

        val PROFILES = mapOf(
            "mapping"     to listOf("camera", "imu"),
            "teleop"      to listOf("camera", "audio", "speaker"),
            "navigation"  to listOf("imu", "gps", "battery"),
            "full_sensor" to listOf("camera", "imu", "gps", "audio", "speaker", "battery", "environment")
        )
    }

    // ── Runtime State ────────────────────────────────────────────────────────
    private val _runtimeState = MutableStateFlow(RuntimeState.STOPPED)
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    private val _clientInfo = MutableStateFlow<ClientInfo?>(null)
    val clientInfo: StateFlow<ClientInfo?> = _clientInfo.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    private val packetsReceivedCount = AtomicLong(0)

    // ── Hardware Drivers ─────────────────────────────────────────────────────
    private lateinit var cameraManager:      CameraManager
    private lateinit var imuManager:         ImuManager
    private lateinit var environmentManager: EnvironmentManager
    private lateinit var motionManager:      MotionManager
    private lateinit var audioRuntime:       AudioRuntime
    private lateinit var batteryManager:     BatteryManager
    private lateinit var speakerManager:     SpeakerManager
    private lateinit var gpsManager:         GpsManager

    // ── Transport ────────────────────────────────────────────────────────────
    private lateinit var transportManager: TransportManager
    private lateinit var tcpTransport:     TcpTransport
    lateinit var ros2Runtime:      Ros2Runtime
    val ros2Status: StateFlow<Ros2Status> get() = ros2Runtime.status

    // ── Core ─────────────────────────────────────────────────────────────────
    private lateinit var benchmarkProcessor:  BenchmarkProcessor
    private lateinit var cameraPipeline:      CameraPipeline
    private lateinit var diagnosticsManager:  DiagnosticsManager
    private lateinit var moduleRegistry:      ModuleRegistry
    private lateinit var capabilityManager:   CapabilityManager
    private lateinit var commandDispatcher:   CommandDispatcher

    private var activeCameraConfig = CameraConfig()
    @Volatile private var isRunning  = false
    @Volatile private var exitRequested = false
    private var restartCount = 0
    private var pendingStart = false
    private var cameraInitializationInProgress = false
    private var deviceId: Byte = 0
    private val heartbeatSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private lateinit var heartbeatHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val s = benchmarkProcessor.snapshot
                diagnosticsManager.logHeartbeat(
                    fps           = s.instantFps,
                    latency       = s.latencyMs,
                    activeModules = moduleRegistry.getRunningModules(),
                    tcpTransport  = tcpTransport
                )

                val uptimeMs = benchmarkProcessor.getRunningTimeMs()

                // ── Send HEARTBEAT packet to client ──
                if (tcpTransport.isConnected) {
                    val payload = PacketSerializer.heartbeat(uptimeMs)
                    val packet = AsrPacket.encode(
                        topicId = TopicId.HEARTBEAT.value,
                        seq = heartbeatSeq.getAndIncrement(),
                        payload = payload,
                        deviceId = deviceId
                    )
                    transportManager.send(packet)
                }

                updateDiagnosticsSnapshot(heartbeatAt = System.currentTimeMillis())

                heartbeatHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun updateDiagnosticsSnapshot(heartbeatAt: Long? = null) {
        val previous = _diagnostics.value
        val tcpEnabled = transportManager.isTransportEnabled(TCP_TRANSPORT_TYPE)
        _diagnostics.value = DiagnosticsSnapshot(
            packetsSent = transportManager.totalPacketsSent,
            packetsReceived = packetsReceivedCount.get(),
            queueDepth = tcpTransport.queueDepth,
            droppedPackets = transportManager.totalPacketDrops + tcpTransport.queueDrops,
            reconnectCount = transportManager.reconnectCount,
            socketState = when {
                !tcpEnabled -> "DISABLED"
                tcpTransport.isConnected -> "CONNECTED"
                else -> "CONNECTING"
            },
            lastHeartbeat = when {
                !isRunning -> 0L
                heartbeatAt != null -> heartbeatAt
                else -> previous.lastHeartbeat
            },
            uptimeMs = if (isRunning) benchmarkProcessor.getRunningTimeMs() else 0L
        )
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): AsrService = this@AsrService
    }

    fun getModuleStatus(): Map<String, String> = moduleRegistry.getModuleStatus()

    fun getEnabledSensorModuleIds(): Set<String> = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(PREF_ENABLED_SENSOR_MODULES, null)
        ?.intersect(CONFIGURABLE_SENSOR_MODULE_IDS.toSet())
        ?: CONFIGURABLE_SENSOR_MODULE_IDS.toSet()

    fun setEnabledSensorModuleIds(moduleIds: Set<String>) {
        val selected = moduleIds.intersect(CONFIGURABLE_SENSOR_MODULE_IDS.toSet())
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(PREF_ENABLED_SENSOR_MODULES, selected)
            .apply()

        // Runtime start/stop/restart owns module transitions while it is pending.
        // The saved selection is read by startSensorModules() after that transition.
        if (!isRunning || pendingStart || _runtimeState.value == RuntimeState.STOPPING) return
        val alwaysOn = setOf("speaker", "diagnostics")
        val desired = selected + alwaysOn
        val managed = CONFIGURABLE_SENSOR_MODULE_IDS.filterNot { it == "camera" } + alwaysOn
        val running = moduleRegistry.getRunningModules().toSet()
        managed.filter { it in desired && it !in running }.forEach(moduleRegistry::startModule)
        managed.filter { it !in desired && it in running }.forEach(moduleRegistry::stopModule)
        if ("camera" in selected && "camera" !in running) {
            startCameraModuleWhenReady()
        } else if ("camera" !in selected && "camera" in running) {
            moduleRegistry.stopModule("camera")
        }
        ensureForeground(sensorRuntime = true)
    }

    private fun startCameraModuleWhenReady() {
        if (!isRunning || "camera" !in getEnabledSensorModuleIds()) return
        if (cameraManager.isCameraProviderReady()) {
            moduleRegistry.startModule("camera")
            return
        }
        if (cameraInitializationInProgress) return
        cameraInitializationInProgress = true
        cameraManager.initialize(
            onReady = {
                cameraInitializationInProgress = false
                if (isRunning && _runtimeState.value != RuntimeState.STOPPING &&
                    "camera" in getEnabledSensorModuleIds()) {
                    moduleRegistry.startModule("camera")
                }
            },
            onError = { error ->
                cameraInitializationInProgress = false
                cameraManager.state = ModuleState.ERROR
                Log.e(TAG, "Camera initialization failed while applying sensor selection", error)
            }
        )
    }

    fun getDeviceNodeName(): String {
        val alias = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_NODE_ALIAS, null) ?: "asr_android"
        return "${alias}_${deviceId.toInt() and 0xFF}"
    }

    fun startModule(id: String) {
        if (id == "transport") setTcpEnabled(true) else moduleRegistry.startModule(id)
    }

    fun stopModule(id: String) {
        if (id == "transport") setTcpEnabled(false) else moduleRegistry.stopModule(id)
    }

    // ── Broadcast receivers ──────────────────────────────────────────────────

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STATUS     -> logStatus()
                ACTION_TCP_CONNECT -> handleTcpConnect(intent)
                ACTION_DEV_MODE   -> handleDevMode(intent)
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        heartbeatHandler = Handler(Looper.getMainLooper())
        
        // ── Acquire Locks for Robotics Workload ──
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AsrRuntime:WakeLock")

        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AsrRuntime:WifiLock")

        multicastLock = wm.createMulticastLock("AsrRuntime:MulticastLock")
        multicastLock?.setReferenceCounted(false)

        Log.i(TAG, "[ASR] Starting Runtime v${BuildConfig.VERSION_NAME}")

        // ── 1. Stable device ID (SharedPreferences UUID + Android ID) ──────
        deviceId = getOrCreateDeviceId()
        ros2Runtime = Ros2Runtime(this).apply {
            listener = this@AsrService
        }

        // ── 2. Transport layer ─────────────────────────────────────────────
        transportManager = TransportManager()
        val transportPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tcpTransport = TcpTransport(
            host             = transportPrefs.getString(PREF_TCP_HOST, "127.0.0.1") ?: "127.0.0.1",
            port             = transportPrefs.getInt(PREF_TCP_PORT, 4242).coerceIn(1, 65535),
            onPacketReceived = { 
                packetsReceivedCount.incrementAndGet()
                commandDispatcher.dispatch(it)
                updateDiagnosticsSnapshot()
            },
            onReconnect      = { transportManager.notifyReconnect() },
            onConnectionStateChanged = ::handleTcpConnectionStateChanged
        )
        transportManager.addTransport(LogTransport())
        transportManager.addTransport(tcpTransport)

        // ── 3. Diagnostics and benchmark ──────────────────────────────────
        benchmarkProcessor = BenchmarkProcessor(this)
        diagnosticsManager = DiagnosticsManager(this, transportManager)

        // ── 4. Camera pipeline ────────────────────────────────────────────
        cameraPipeline = CameraPipeline(
            transportManager = transportManager,
            deviceId         = deviceId,
            compressor       = JpegCompressor(quality = 85),
            onRosCompressedFrame = { data, width, height, timestampNs ->
                ros2Runtime.publishCameraJpeg(data, width, height, timestampNs)
            }
        )

        // ── 5. Hardware drivers ───────────────────────────────────────────
        cameraManager = CameraManager(
            context         = this,
            onCameraError   = {
                benchmarkProcessor.onCameraError()
                if (isRunning) _runtimeState.value = RuntimeState.ERROR
            },
            onThermalStatus = { status ->
                Log.w(TAG, "[THERMAL] ${status.name}")
                benchmarkProcessor.updateThermalStatus(status)
            },
            onCameraStarted = {
                if (isRunning && _runtimeState.value == RuntimeState.STARTING) {
                    _runtimeState.value = RuntimeState.STREAMING
                }
            }
        ).apply {
            this.lifecycleOwner = this@AsrService
            this.cameraPipeline = this@AsrService.cameraPipeline
            this.benchmarkProcessor = this@AsrService.benchmarkProcessor
            this.config = activeCameraConfig
        }

        imuManager         = ImuManager(this, transportManager, deviceId) { accel, gyro, rotation, timestampNs ->
            ros2Runtime.publishImu(accel, gyro, rotation, timestampNs)
        }
        environmentManager = EnvironmentManager(this, transportManager, deviceId) { kind, value, timestampNs ->
            ros2Runtime.publishEnvironment(kind, value.toDouble(), timestampNs)
        }
        motionManager      = MotionManager(this) { steps ->
            ros2Runtime.publishSteps(steps)
        }
        audioRuntime       = AudioRuntime(this, transportManager, deviceId) { data ->
            ros2Runtime.publishAudio(data)
        }
        batteryManager     = BatteryManager(this, transportManager, deviceId) { percentage, temperature, voltage, charging, timestampNs ->
            ros2Runtime.publishBattery(percentage, temperature, voltage, charging, timestampNs)
        }
        speakerManager     = SpeakerManager(this)
        gpsManager         = GpsManager(this, transportManager, deviceId) { location ->
            ros2Runtime.publishGps(
                location.latitude,
                location.longitude,
                location.altitude,
                location.accuracy,
                location.elapsedRealtimeNanos
            )
        }
        capabilityManager  = CapabilityManager(this)

        // ── 6. Module registry ────────────────────────────────────────────
        moduleRegistry = ModuleRegistry(listOf(
            cameraManager,
            imuManager,
            environmentManager,
            motionManager,
            audioRuntime,
            batteryManager,
            speakerManager,
            gpsManager,
            transportManager,
            diagnosticsManager
        ))

        // ── 7. Command dispatcher + handler registration ──────────────────
        commandDispatcher = CommandDispatcher(transportManager, deviceId)
        registerCommandHandlers()

        // CameraX is initialized on demand when the sensor runtime starts.
        // TCP-only and DDS-only operation do not touch the camera pipeline.

        // ── 9. Broadcast control ──────────────────────────────────────────
        val filter = IntentFilter().apply {
            addAction(ACTION_STATUS)
            addAction(ACTION_TCP_CONNECT)
            addAction(ACTION_DEV_MODE)
            addAction(ACTION_SET_ALIAS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }

        Log.i(TAG, "[ASR] onCreate complete. DeviceId=$deviceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            Log.i(TAG, "[ASR] Sticky restart (null intent) — restoring enabled modules")
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val runtimeEnabled = prefs.getBoolean(PREF_RUNTIME_ENABLED, false)
            val tcpEnabled = prefs.getBoolean(PREF_TCP_ENABLED, false)
            val ros2Enabled = prefs.getBoolean(PREF_ROS2_ENABLED, false)
            val wifiDdsEnabled = prefs.getBoolean(PREF_DDS_WIFI_ENABLED, ros2Enabled)
            val usbDdsEnabled = prefs.getBoolean(PREF_DDS_USB_ENABLED, false)
            if (runtimeEnabled || tcpEnabled || ros2Enabled) ensureForeground(sensorRuntime = runtimeEnabled)
            if (tcpEnabled) setTcpEnabled(true)
            if (ros2Enabled || wifiDdsEnabled || usbDdsEnabled) {
                setRos2TransportPaths(wifiDdsEnabled, usbDdsEnabled)
            }
            if (runtimeEnabled) handleStart()
            return START_STICKY
        }

        if (intent.action == ACTION_START || intent.action == ACTION_RESTART ||
            intent.action == ACTION_TCP_ENABLE || intent.action == ACTION_TCP_CONNECT ||
            intent.action == ACTION_ROS2_ENABLE || intent.action == ACTION_DDS_PATHS_SET) {
            ensureForeground(sensorRuntime = isRunning || pendingStart)
        }

        when (intent.action) {
            ACTION_START          -> {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_RUNTIME_ENABLED, true).apply()
                restoreConfiguredRos2PathIfNeeded()
                handleStart()
            }
            ACTION_SET_ALIAS      -> {
                val alias = intent.getStringExtra("alias")
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_NODE_ALIAS, alias).apply()
                Log.i(TAG, "Node alias set to: $alias")
                restartRos2IfEnabled()
            }
            ACTION_STOP           -> { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_RUNTIME_ENABLED, false).apply(); serviceScope.launch { handleStop() } }
            ACTION_RESTART        -> serviceScope.launch { handleRestart() }
            ACTION_SPEAK          -> {
                val text = intent.getStringExtra("text") ?: "ASR speaker test successful"
                speakerManager.speak(text)
            }
            ACTION_MODULE_START   -> intent.getStringExtra("module_id")?.let {
                Log.i(TAG, "[CMD] Starting module: $it")
                startModule(it)
            }
            ACTION_MODULE_STOP    -> intent.getStringExtra("module_id")?.let {
                Log.i(TAG, "[CMD] Stopping module: $it")
                stopModule(it)
            }
            ACTION_PROFILE_ENABLE -> intent.getStringExtra("profile")?.let { profileId ->
                PROFILES[profileId]?.forEach { modId ->
                    Log.i(TAG, "[CMD] Profile '$profileId' enabling: $modId")
                    moduleRegistry.startModule(modId)
                }
            }
            ACTION_CAMERA_CONFIG  -> handleCameraConfig(intent)
            ACTION_FLASHLIGHT     -> {
                val on = intent.getBooleanExtra("on", false)
                cameraManager.setTorch(on)
                Log.i(TAG, "[CMD] Flashlight state set to: on=$on")
            }
            ACTION_TCP_CONNECT    -> handleTcpConnect(intent)
            ACTION_TCP_ENABLE     -> setTcpEnabled(
                enabled = true,
                host = intent.getStringExtra("host") ?: tcpTransport.host,
                port = intent.getIntExtra("port", tcpTransport.port)
            )
            ACTION_TCP_DISABLE    -> setTcpEnabled(false)
            ACTION_ROS2_ENABLE    -> setRos2Enabled(true)
            ACTION_ROS2_DISABLE   -> setRos2Enabled(false)
            ACTION_DDS_PATHS_SET  -> {
                if (intent.hasExtra("discovery_server")) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putString(PREF_DDS_DISCOVERY_SERVER, intent.getStringExtra("discovery_server").orEmpty())
                        .apply()
                }
                setRos2TransportPaths(
                    intent.getBooleanExtra("wifi_enabled", false),
                    intent.getBooleanExtra("usb_enabled", false)
                )
            }
            ACTION_DEV_MODE       -> handleDevMode(intent)
        }
        return START_STICKY
    }

    private fun registerCommandHandlers() {
        commandDispatcher.register(object : CommandHandler {
            override val command = "module.start"
            override fun execute(params: JSONObject): Result<String> {
                val id = params.getString("id")
                startModule(id)
                return Result.success("Module '$id' started")
            }
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "module.stop"
            override fun execute(params: JSONObject): Result<String> {
                val id = params.getString("id")
                stopModule(id)
                return Result.success("Module '$id' stopped")
            }
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "camera.set_fps"
            override fun execute(params: JSONObject): Result<String> {
                val fps = params.getInt("fps")
                cameraManager.configure("""{"fps":$fps}""")
                return Result.success("FPS set to $fps")
            }
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "camera.set_lens"
            override fun execute(params: JSONObject): Result<String> {
                val lens = params.getString("lens")
                cameraManager.configure("""{"lens":"$lens"}""")
                return Result.success("Lens set to $lens")
            }
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "camera.set_encoding"
            override fun execute(params: JSONObject): Result<String> {
                val enc = params.getString("encoding")
                cameraManager.configure("""{"encoding":"$enc"}""")
                return Result.success("Encoding set to $enc")
            }
        })
        val speakerHandler = object : CommandHandler {
            override val command = "speaker.speak"
            override fun execute(params: JSONObject): Result<String> {
                val text = params.optString("text", "ASR speaker test")
                if (speakerManager.state == ModuleState.STOPPED) {
                    Log.i(TAG, "[CMD] Auto-starting speaker module...")
                    speakerManager.start()
                    val deadline = System.currentTimeMillis() + 2000
                    while (!speakerManager.isTtsReady && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50)
                    }
                }
                val success = speakerManager.speak(text)
                return if (success) Result.success("Speaking: $text") else Result.failure(Exception("Speaker not ready"))
            }
        }
        commandDispatcher.register(speakerHandler)
        commandDispatcher.register(object : CommandHandler {
            override val command = "speak"
            override fun execute(params: JSONObject): Result<String> = speakerHandler.execute(params)
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "profile.enable"
            override fun execute(params: JSONObject): Result<String> {
                val profileId = params.getString("profile")
                val modules = PROFILES[profileId]
                    ?: return Result.failure(IllegalArgumentException("Unknown profile: $profileId"))
                modules.forEach { moduleRegistry.startModule(it) }
                return Result.success("Profile '$profileId' enabled: $modules")
            }
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "flashlight.set"
            override fun execute(params: JSONObject): Result<String> {
                val isOn = params.optBoolean("on", false)
                val success = cameraManager.setTorch(isOn)
                return if (success) Result.success("Flashlight set to $isOn") else Result.failure(Exception("Camera not active or torch unavailable"))
            }
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "runtime.stop"
            override fun execute(params: JSONObject): Result<String> {
                Log.i(TAG, "[CMD] Stop ASR Runtime requested via TCP command")
                stopRuntime()
                return Result.success("ASR Runtime modules stopped")
            }
        })
    }

    fun startRuntime() {
        if (isRunning) return
        _runtimeState.value = RuntimeState.STARTING
        handleStart()
    }

    fun stopRuntime() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_RUNTIME_ENABLED, false).apply()
        serviceScope.launch {
            if (isRunning || pendingStart) _runtimeState.value = RuntimeState.STOPPING
            handleStop()
            _runtimeState.value = RuntimeState.STOPPED
            stopForegroundIfIdle()
        }
    }

    /** Stop all runtime work and the started foreground service before closing the UI. */
    fun shutdownForExit(onComplete: () -> Unit) {
        synchronized(modeRequestLock) {
            if (exitRequested) return
            exitRequested = true
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_RUNTIME_ENABLED, false)
                .putBoolean(PREF_TCP_ENABLED, false)
                .putBoolean(PREF_ROS2_ENABLED, false)
                .putBoolean(PREF_DDS_WIFI_ENABLED, false)
                .putBoolean(PREF_DDS_USB_ENABLED, false)
                .apply()
        }
        serviceScope.launch {
            _runtimeState.value = RuntimeState.STOPPING
            pendingStart = false
            handleStop()
            withContext(Dispatchers.IO) {
                tcpLifecycleMutex.withLock { transportManager.stopTransport(TCP_TRANSPORT_TYPE) }
                ros2LifecycleMutex.withLock {
                    ros2Runtime.setEnabled(false)
                    releaseMulticastLock()
                }
            }
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            onComplete()
        }
    }

    fun restartRuntime() {
        serviceScope.launch {
            _runtimeState.value = RuntimeState.STOPPING
            handleRestart()
        }
    }

    fun resetRuntime() {
        Log.w(TAG, "[DEV] Emergency Reset Triggered")
        serviceScope.launch {
            stopSensorModules()
            packetsReceivedCount.set(0)
            handleRestart()
        }
    }

    fun reconnectTransport() {
        setTcpEnabled(true, tcpTransport.host, tcpTransport.port)
    }

    fun reconnectTransport(host: String, port: Int) {
        setTcpEnabled(true, host, port)
    }

    fun stopTransport() {
        setTcpEnabled(false)
    }

    fun setTcpEnabled(enabled: Boolean, host: String = tcpTransport.host, port: Int = tcpTransport.port) {
        val safePort = port.takeIf { it in 1..65535 } ?: 4242
        synchronized(modeRequestLock) {
            if (exitRequested && enabled) return
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_TCP_ENABLED, enabled)
                .putString(PREF_TCP_HOST, host.ifBlank { "127.0.0.1" })
                .putInt(PREF_TCP_PORT, safePort)
                .apply()
            pendingModeTransitions.incrementAndGet()
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                tcpLifecycleMutex.withLock {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val shouldEnable = !exitRequested && prefs.getBoolean(PREF_TCP_ENABLED, false)
                    if (!shouldEnable) {
                        transportManager.stopTransport(TCP_TRANSPORT_TYPE)
                        _clientInfo.value = null
                    } else {
                        val configuredHost = prefs.getString(PREF_TCP_HOST, "127.0.0.1") ?: "127.0.0.1"
                        val configuredPort = prefs.getInt(PREF_TCP_PORT, 4242).coerceIn(1, 65535)
                        if (tcpTransport.host != configuredHost || tcpTransport.port != configuredPort) {
                            if (transportManager.isTransportEnabled(TCP_TRANSPORT_TYPE)) {
                                transportManager.stopTransport(TCP_TRANSPORT_TYPE)
                            }
                        }
                        tcpTransport.host = configuredHost
                        tcpTransport.port = configuredPort
                        transportManager.startTransport(TCP_TRANSPORT_TYPE)
                    }
                }
            } finally {
                pendingModeTransitions.decrementAndGet()
                updateDiagnosticsSnapshot()
                withContext(Dispatchers.Main.immediate) { stopForegroundIfIdle() }
            }
        }
    }

    fun setRos2Enabled(enabled: Boolean) {
        // Legacy control action maps to the existing Wi-Fi DDS behavior.
        setRos2TransportPaths(wifiEnabled = enabled, usbEnabled = false)
    }

    fun setRos2TransportPaths(wifiEnabled: Boolean, usbEnabled: Boolean) {
        val enabled = wifiEnabled || usbEnabled
        synchronized(modeRequestLock) {
            if (exitRequested && enabled) return
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_DDS_WIFI_ENABLED, wifiEnabled)
                .putBoolean(PREF_DDS_USB_ENABLED, usbEnabled)
                .putBoolean(PREF_ROS2_ENABLED, enabled)
                .apply()
            pendingModeTransitions.incrementAndGet()
        }
        if (enabled) {
            ensureForeground(sensorRuntime = isRunning || pendingStart)
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                ros2LifecycleMutex.withLock {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val shouldEnable = !exitRequested && prefs.getBoolean(PREF_ROS2_ENABLED, false)
                    val currentWifi = prefs.getBoolean(PREF_DDS_WIFI_ENABLED, shouldEnable)
                    val currentUsb = prefs.getBoolean(PREF_DDS_USB_ENABLED, false)
                    val wasEnabled = ros2Runtime.isEnabled()
                    if (shouldEnable && wasEnabled) ros2Runtime.shutdown(disabled = false)
                    ros2Runtime.setTransportPaths(currentWifi, currentUsb)
                    ros2Runtime.setEnabled(shouldEnable)
                    if (shouldEnable) startRos2() else releaseMulticastLock()
                }
            } finally {
                pendingModeTransitions.decrementAndGet()
                updateDiagnosticsSnapshot()
                withContext(Dispatchers.Main.immediate) { stopForegroundIfIdle() }
            }
        }
    }

    private fun stopForegroundIfIdle() {
        if (exitRequested || pendingModeTransitions.get() > 0) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val anyModeEnabled = prefs.getBoolean(PREF_RUNTIME_ENABLED, false) ||
            prefs.getBoolean(PREF_TCP_ENABLED, false) || prefs.getBoolean(PREF_ROS2_ENABLED, false)
        if (!anyModeEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun handleStart() {
        if (exitRequested) return
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_RUNTIME_ENABLED, true).apply()
        ensureForeground(sensorRuntime = true)
        if (isRunning) return
        startSensorRuntimeWhenReady()
    }

    private fun restoreConfiguredRos2PathIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wifiEnabled = prefs.getBoolean(PREF_DDS_WIFI_ENABLED, prefs.getBoolean(PREF_ROS2_ENABLED, false))
        val usbEnabled = prefs.getBoolean(PREF_DDS_USB_ENABLED, false)
        if ((wifiEnabled || usbEnabled) && !ros2Runtime.isEnabled()) {
            setRos2TransportPaths(wifiEnabled, usbEnabled)
        }
    }

    private fun startSensorRuntimeWhenReady() {
        if (exitRequested) return
        if ("camera" !in getEnabledSensorModuleIds()) {
            pendingStart = false
            startInternal()
            return
        }
        if (cameraManager.isCameraProviderReady()) {
            pendingStart = false
            startInternal()
            return
        }
        pendingStart = true
        if (cameraInitializationInProgress) return
        cameraInitializationInProgress = true
        cameraManager.initialize(
            onReady = {
                cameraInitializationInProgress = false
                if (pendingStart && !exitRequested) {
                    pendingStart = false
                    startInternal()
                }
            },
            onError = {
                cameraInitializationInProgress = false
                pendingStart = false
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_RUNTIME_ENABLED, false)
                    .apply()
                _runtimeState.value = RuntimeState.ERROR
                stopForegroundIfIdle()
                if (getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(PREF_TCP_ENABLED, false) || ros2Runtime.isEnabled()) {
                    ensureForeground(sensorRuntime = false)
                }
            }
        )
    }

    private fun ensureForeground(sensorRuntime: Boolean = false) {
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (sensorRuntime) {
                val selected = getEnabledSensorModuleIds()
                var selectedTypes = 0
                if ("camera" in selected) selectedTypes = selectedTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if ("audio" in selected) selectedTypes = selectedTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if ("gps" in selected) selectedTypes = selectedTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (selectedTypes == 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else selectedTypes
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRos2() {
        if (ros2Runtime.isEnabled() && multicastLock?.isHeld != true) {
            runCatching { multicastLock?.acquire() }
                .onFailure { Log.w(TAG, "Unable to acquire DDS multicast lock", it) }
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ros2Runtime.setTransportPaths(
            prefs.getBoolean(PREF_DDS_WIFI_ENABLED, prefs.getBoolean(PREF_ROS2_ENABLED, false)),
            prefs.getBoolean(PREF_DDS_USB_ENABLED, false)
        )
        val alias = prefs.getString(PREF_NODE_ALIAS, null)
        val baseName = alias ?: "asr_android"
        val unsignedId = deviceId.toInt() and 0xFF
        val nodeName = "${baseName}_${unsignedId}"
        val nodeNamespace = "/asr_android_${unsignedId}"
        ros2Runtime.startIfNeeded(nodeName, nodeNamespace)
        // The battery receiver is event-driven and may have no new broadcast
        // after a DDS path restart. Republish its cached value on the new node.
        batteryManager.publishCurrentRosState()
    }

    private fun startInternal() {
        if (exitRequested) return
        _runtimeState.value = RuntimeState.STARTING
        benchmarkProcessor.reset(restartCount)

        // Acquire locks only when active
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        wifiLock?.acquire()

        isRunning = true
        startSensorModules()
        startHeartbeatLoop()
        Log.i(TAG, "[ASR] Runtime startup requested. DeviceId=$deviceId")
    }

    private fun startHeartbeatLoop() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun handleStop() {
        pendingStart = false
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        stopSensorModules()
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()

        isRunning = false
        _runtimeState.value = RuntimeState.STOPPED
        updateDiagnosticsSnapshot()
        stopForegroundIfIdle()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_TCP_ENABLED, false) || prefs.getBoolean(PREF_ROS2_ENABLED, false)) {
            ensureForeground(sensorRuntime = false)
        }
    }

    private fun releaseMulticastLock() {
        if (multicastLock?.isHeld == true) {
            runCatching { multicastLock?.release() }
                .onFailure { Log.w(TAG, "Unable to release DDS multicast lock", it) }
        }
    }

    private fun startSensorModules() {
        val selected = getEnabledSensorModuleIds() + setOf("speaker", "diagnostics")
        SENSOR_RUNTIME_MODULE_IDS.filter { it in selected }.forEach(moduleRegistry::startModule)
    }

    private fun stopSensorModules() {
        SENSOR_RUNTIME_MODULE_IDS.asReversed().forEach { moduleRegistry.stopModule(it) }
    }

    private fun handleRestart() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_RUNTIME_ENABLED, true).apply()
        handleStop()
        restartCount++
        ensureForeground(sensorRuntime = true)
        startSensorRuntimeWhenReady()
    }

    private fun handleCameraConfig(intent: Intent) {
        val lens   = intent.getStringExtra("lens")
        val width  = intent.getIntExtra("width",  activeCameraConfig.resolution.width)
        val height = intent.getIntExtra("height", activeCameraConfig.resolution.height)

        val newLensFacing = when (lens?.lowercase()) {
            "front" -> androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            "back"  -> androidx.camera.core.CameraSelector.LENS_FACING_BACK
            else    -> activeCameraConfig.lensFacing
        }
        activeCameraConfig = activeCameraConfig.copy(
            lensFacing = newLensFacing,
            resolution = android.util.Size(width, height)
        )
        if (cameraManager.state == org.asr.runtime.common.ModuleState.RUNNING) {
            cameraManager.stop()
            cameraManager.config = activeCameraConfig
            cameraManager.start()
        } else {
            cameraManager.config = activeCameraConfig
        }
    }

    private fun handleTcpConnect(intent: Intent) {
        val host = intent.getStringExtra("host") ?: "127.0.0.1"
        val port = intent.getIntExtra("port", 4242)
        reconnectTransport(host, port)
    }

    private fun handleTcpConnectionStateChanged(connected: Boolean) {
        // Ignore a late callback from a socket that has already been replaced.
        if (connected != tcpTransport.isConnected) return
        if (connected) {
            val clientApp = if (tcpTransport.host == "127.0.0.1") "ASR Studio (ADB)" else "ASR Studio (Wi-Fi)"
            _clientInfo.value = ClientInfo(
                application = clientApp,
                host = tcpTransport.host,
                port = tcpTransport.port,
                connectedSince = System.currentTimeMillis()
            )
            val payload = PacketSerializer.capabilities(this, deviceId)
            val capPacket = AsrPacket.encode(
                topicId = TopicId.CAPABILITIES.value,
                seq = 0,
                payload = payload,
                deviceId = deviceId
            )
            transportManager.send(capPacket)
        } else {
            _clientInfo.value = null
        }
        updateDiagnosticsSnapshot()
    }

    private fun restartRos2IfEnabled() {
        if (exitRequested) return
        serviceScope.launch(Dispatchers.IO) {
            ros2LifecycleMutex.withLock {
                val shouldRun = !exitRequested && getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_ROS2_ENABLED, false)
                if (shouldRun) {
                    ros2Runtime.shutdown(disabled = false)
                    startRos2()
                }
            }
        }
    }

    private fun setRos2DomainId(value: Int) {
        if (exitRequested) return
        serviceScope.launch(Dispatchers.IO) {
            ros2LifecycleMutex.withLock {
                ros2Runtime.setDomainId(value)
                val shouldRun = !exitRequested && getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_ROS2_ENABLED, false)
                if (shouldRun) {
                    ros2Runtime.shutdown(disabled = false)
                    startRos2()
                }
            }
        }
    }

    private fun handleDevMode(intent: Intent) {
        val flag  = intent.getStringExtra("flag")  ?: return
        if (flag == "ros2_enabled") {
            val value = intent.getBooleanExtra("value", false)
            setRos2Enabled(value)
            return
        }
        if (flag == "ros2_domain_id") {
            val value = intent.getIntExtra("value", 0)
            setRos2DomainId(value)
            return
        }
        val value = intent.getBooleanExtra("value", false)
        DevModeConfig.applyFlag(flag, value)
    }

    private fun logStatus() {
        val s = benchmarkProcessor.snapshot
        val moduleStatus = moduleRegistry.getModuleStatus()
        val capabilities = capabilityManager.discover()

        Log.i(TAG, buildString {
            appendLine()
            appendLine("============= ASR Status =============")
            appendLine("Running      : ${if (isRunning) "YES" else "NO"}")
            appendLine("DeviceId     : $deviceId")
            appendLine("FPS          : ${String.format(Locale.US, "%.1f", s.instantFps)}")
            appendLine("Latency      : ${String.format(Locale.US, "%.1f", s.latencyMs)} ms")
            appendLine("Battery      : ${String.format(Locale.US, "%.1f", batteryManager.level)}%  ${batteryManager.temperature}°C")
            appendLine("Uptime       : ${s.uptimeMs / 1000}s")
            appendLine("Capabilities : $capabilities")
            appendLine("[MODULE STATES]")
            moduleStatus.entries.forEach { appendLine("  ${it.key.padEnd(12)}: ${it.value}") }
            append("======================================")
        })
    }

    private fun getOrCreateDeviceId(): Byte {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        
        // To handle copied/cloned pref files, we check if the device ID matches the current ANDROID_ID
        val storedAndroidId = prefs.getString("generated_android_id", null)
        var uuidStr = prefs.getString(PREF_DEVICE_ID, null)

        if (uuidStr == null || storedAndroidId != androidId) {
            uuidStr = UUID.randomUUID().toString()
            prefs.edit()
                .putString(PREF_DEVICE_ID, uuidStr)
                .putString("generated_android_id", androidId)
                .apply()
            Log.i(TAG, "Regenerated unique device identity for ANDROID_ID: $androidId")
        }

        // Salt the persistent UUID with the hardware ANDROID_ID to ensure uniqueness across devices
        val combinedHash = uuidStr.hashCode() xor androidId.hashCode()
        var id = combinedHash and 0xFF
        if (id == 0) id = 1 // Avoid 0 as it often denotes 'unknown' or 'broadcast'
        return id.toByte()
    }

    override fun onFlashlightCommand(on: Boolean) {
        cameraManager.setTorch(on)
    }

    override fun onSpeakerCommand(text: String) {
        speakerManager.speak(text)
    }

    override fun onDdsInterfacesChanged() {
        serviceScope.launch(Dispatchers.IO) {
            ros2LifecycleMutex.withLock {
                if (exitRequested || !ros2Runtime.isEnabled()) return@withLock
                Log.i(TAG, "Restarting ROS 2 participant after a selected network interface changed")
                ros2Runtime.shutdown(disabled = false)
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ros2Runtime.setTransportPaths(
                    prefs.getBoolean(PREF_DDS_WIFI_ENABLED, prefs.getBoolean(PREF_ROS2_ENABLED, false)),
                    prefs.getBoolean(PREF_DDS_USB_ENABLED, false)
                )
                startRos2()
            }
            updateDiagnosticsSnapshot()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching { ros2Runtime.shutdown() }
        runCatching { moduleRegistry.releaseAll() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
        try { unregisterReceiver(controlReceiver) } catch (e: Exception) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ASR Runtime", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ASR Runtime Active")
            .setContentText("DeviceId: $deviceId")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
}
