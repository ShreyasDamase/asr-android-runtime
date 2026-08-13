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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import org.asr.runtime.BuildConfig
import org.asr.runtime.R
import org.asr.runtime.audio.AudioRuntime
import org.asr.runtime.audio.SpeakerManager
import org.asr.runtime.battery.BatteryManager
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
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Foreground Service that owns and orchestrates the ASR hardware runtime.
 */
class AsrService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG            = "PhoneCamera"
        private const val CHANNEL_ID     = "AsrRuntimeService"
        private const val NOTIFICATION_ID = 101
        private const val PREFS_NAME     = "asr_prefs"
        private const val PREF_DEVICE_ID = "device_id"

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
        const val ACTION_TCP_CONNECT    = "org.asr.runtime.TCP_CONNECT"
        const val ACTION_DEV_MODE       = "org.asr.runtime.DEV_MODE"

        val PROFILES = mapOf(
            "mapping"     to listOf("camera", "imu"),
            "teleop"      to listOf("camera", "audio", "speaker"),
            "navigation"  to listOf("imu", "gps", "battery"),
            "full_sensor" to listOf("camera", "imu", "gps", "audio", "battery", "environment")
        )
    }

    // ── Runtime State ────────────────────────────────────────────────────────
    private val _runtimeState = MutableStateFlow(RuntimeState.STOPPED)
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    private val _clientInfo = MutableStateFlow<ClientInfo?>(null)
    val clientInfo: StateFlow<ClientInfo?> = _clientInfo.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    private var packetsReceivedCount = 0L

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

    // ── Core ─────────────────────────────────────────────────────────────────
    private lateinit var benchmarkProcessor:  BenchmarkProcessor
    private lateinit var cameraPipeline:      CameraPipeline
    private lateinit var diagnosticsManager:  DiagnosticsManager
    private lateinit var moduleRegistry:      ModuleRegistry
    private lateinit var capabilityManager:   CapabilityManager
    private lateinit var commandDispatcher:   CommandDispatcher

    private var activeCameraConfig = CameraConfig()
    private var isRunning  = false
    private var restartCount = 0
    private var pendingStart = false
    private var deviceId: Byte = 0
    private val heartbeatSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private lateinit var heartbeatHandler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

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

                // ── Send HEARTBEAT packet to client ──
                if (tcpTransport.isConnected) {
                    val payload = PacketSerializer.heartbeat(s.uptimeMs)
                    val packet = AsrPacket.encode(
                        topicId = TopicId.HEARTBEAT.value,
                        seq = heartbeatSeq.getAndIncrement(),
                        payload = payload,
                        deviceId = deviceId
                    )
                    transportManager.send(packet)
                }

                // Update UI Diagnostics
                _diagnostics.value = DiagnosticsSnapshot(
                    packetsSent      = transportManager.totalPacketsSent,
                    packetsReceived  = packetsReceivedCount,
                    queueDepth       = tcpTransport.queueDepth,
                    droppedPackets   = transportManager.totalPacketDrops,
                    reconnectCount   = transportManager.reconnectCount,
                    socketState      = if (tcpTransport.isConnected) "CONNECTED" else "CONNECTING",
                    lastHeartbeat    = System.currentTimeMillis(),
                    uptimeMs         = s.uptimeMs
                )

                heartbeatHandler.postDelayed(this, 1000)
            }
        }
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): AsrService = this@AsrService
    }

    fun getModuleStatus(): Map<String, String> = moduleRegistry.getModuleStatus()

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
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hour timeout

        val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AsrRuntime:WifiLock")
        wifiLock?.acquire()

        Log.i(TAG, "[ASR] Starting Runtime v${BuildConfig.VERSION_NAME}")

        // ── 1. Stable device ID (SharedPreferences UUID) ──────────────────
        deviceId = getOrCreateDeviceId()

        // ── 2. Transport layer ─────────────────────────────────────────────
        transportManager = TransportManager()
        tcpTransport = TcpTransport(
            host             = "127.0.0.1",
            port             = 4242,
            onPacketReceived = { 
                packetsReceivedCount++
                commandDispatcher.dispatch(it) 
            },
            onReconnect      = { transportManager.notifyReconnect() },
            onConnectionStateChanged = { connected ->
                if (connected) {
                    val clientApp = if (tcpTransport.host == "127.0.0.1") "ASR Studio (ADB)" else "ASR Studio (Wi-Fi)"
                    _clientInfo.value = ClientInfo(
                        application = clientApp,
                        host = tcpTransport.host,
                        port = tcpTransport.port,
                        connectedSince = System.currentTimeMillis()
                    )
                    if (_runtimeState.value == RuntimeState.CONNECTING) {
                        _runtimeState.value = RuntimeState.CONNECTED
                    }
                    
                    // ── Send Handshake: CAPABILITIES ──
                    val capPayload = capabilityManager.discover().let {
                        PacketSerializer.capabilities(this@AsrService, deviceId)
                    }
                    val capPacket = AsrPacket.encode(
                        topicId = TopicId.CAPABILITIES.value,
                        seq = 0,
                        payload = capPayload,
                        deviceId = deviceId
                    )
                    transportManager.send(capPacket)

                } else {
                    _clientInfo.value = null
                    if (_runtimeState.value == RuntimeState.CONNECTED || _runtimeState.value == RuntimeState.STREAMING) {
                        _runtimeState.value = RuntimeState.CONNECTING
                    }
                }
            }
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
            compressor       = JpegCompressor(quality = 85)
        )

        // ── 5. Hardware drivers ───────────────────────────────────────────
        cameraManager = CameraManager(
            context         = this,
            onCameraError   = { benchmarkProcessor.onCameraError() },
            onThermalStatus = { status ->
                Log.w(TAG, "[THERMAL] ${status.name}")
                benchmarkProcessor.updateThermalStatus(status)
            }
        ).apply {
            this.lifecycleOwner = this@AsrService
            this.cameraPipeline = this@AsrService.cameraPipeline
            this.benchmarkProcessor = this@AsrService.benchmarkProcessor
            this.config = activeCameraConfig
        }

        imuManager         = ImuManager(this, transportManager, deviceId)
        environmentManager = EnvironmentManager(this, transportManager, deviceId)
        motionManager      = MotionManager(this)
        audioRuntime       = AudioRuntime(this)
        batteryManager     = BatteryManager(this, transportManager, deviceId)
        speakerManager     = SpeakerManager(this)
        gpsManager         = GpsManager(this, transportManager, deviceId)
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

        // ── 8. Camera async init ──────────────────────────────────────────
        cameraManager.initialize(onReady = {
            if (pendingStart) { pendingStart = false; startInternal() }
        })

        // ── 9. Broadcast control ──────────────────────────────────────────
        val filter = IntentFilter().apply {
            addAction(ACTION_STATUS)
            addAction(ACTION_TCP_CONNECT)
            addAction(ACTION_DEV_MODE)
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
        
        // On modern Android, we MUST call startForeground quickly if started via startForegroundService
        if (intent?.action == ACTION_START || intent?.action == ACTION_RESTART) {
            ensureForeground()
        }

        when (intent?.action) {
            ACTION_START          -> handleStart()
            ACTION_STOP           -> handleStop()
            ACTION_RESTART        -> handleRestart()
            ACTION_SPEAK          -> intent.getStringExtra("text")?.let { speakerManager.speak(it) }
            ACTION_MODULE_START   -> intent.getStringExtra("module_id")?.let {
                Log.i(TAG, "[CMD] Starting module: $it")
                moduleRegistry.startModule(it)
            }
            ACTION_MODULE_STOP    -> intent.getStringExtra("module_id")?.let {
                Log.i(TAG, "[CMD] Stopping module: $it")
                moduleRegistry.stopModule(it)
            }
            ACTION_PROFILE_ENABLE -> intent.getStringExtra("profile")?.let { profileId ->
                PROFILES[profileId]?.forEach { modId ->
                    Log.i(TAG, "[CMD] Profile '$profileId' enabling: $modId")
                    moduleRegistry.startModule(modId)
                }
            }
            ACTION_CAMERA_CONFIG  -> handleCameraConfig(intent)
            ACTION_TCP_CONNECT    -> handleTcpConnect(intent)
            ACTION_DEV_MODE       -> handleDevMode(intent)
        }
        return START_STICKY
    }

    // ── Command handler registration ─────────────────────────────────────────

    private fun registerCommandHandlers() {
        // Module lifecycle
        commandDispatcher.register(object : CommandHandler {
            override val command = "module.start"
            override fun execute(params: JSONObject): Result<String> {
                val id = params.getString("id")
                moduleRegistry.startModule(id)
                return Result.success("Module '$id' started")
            }
        })
        commandDispatcher.register(object : CommandHandler {
            override val command = "module.stop"
            override fun execute(params: JSONObject): Result<String> {
                val id = params.getString("id")
                moduleRegistry.stopModule(id)
                return Result.success("Module '$id' stopped")
            }
        })
        // Camera commands
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
                val enc = params.getString("encoding") // "JPEG" or "RAW_YUV"
                cameraManager.configure("""{"encoding":"$enc"}""")
                return Result.success("Encoding set to $enc")
            }
        })
        // Speaker
        commandDispatcher.register(object : CommandHandler {
            override val command = "speak"
            override fun execute(params: JSONObject): Result<String> {
                val text = params.getString("text")
                speakerManager.speak(text)
                return Result.success("Speaking: $text")
            }
        })
        // Profile
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
        // Runtime stop
        commandDispatcher.register(object : CommandHandler {
            override val command = "runtime.stop"
            override fun execute(params: JSONObject): Result<String> {
                Log.i(TAG, "[CMD] Stop ASR Runtime requested via TCP command")
                moduleRegistry.stopAll()
                return Result.success("ASR Runtime modules stopped")
            }
        })

        Log.i(TAG, "[CMD] Registered ${commandDispatcher.registeredCommands().size} command handlers: ${commandDispatcher.registeredCommands()}")
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    // ── Public Controller Methods ───────────────────────────────────────────

    fun startRuntime() {
        if (isRunning) return
        _runtimeState.value = RuntimeState.STARTING
        handleStart()
    }

    fun stopRuntime() {
        if (!isRunning) return
        serviceScope.launch(Dispatchers.Default) {
            _runtimeState.value = RuntimeState.STOPPING
            handleStop()
            _runtimeState.value = RuntimeState.STOPPED
        }
    }

    fun restartRuntime() {
        serviceScope.launch(Dispatchers.Default) {
            _runtimeState.value = RuntimeState.STOPPING
            handleRestart()
            _runtimeState.value = RuntimeState.STARTING
        }
    }

    fun resetRuntime() {
        Log.w(TAG, "[DEV] Emergency Reset Triggered")
        serviceScope.launch(Dispatchers.Default) {
            moduleRegistry.stopAll()
            packetsReceivedCount = 0
            handleRestart()
        }
    }

    fun reconnectTransport() {
        Log.i(TAG, "[CMD] Manual Transport Reconnect (current host=${tcpTransport.host}:${tcpTransport.port})")
        serviceScope.launch(Dispatchers.IO) {
            tcpTransport.disconnect()
            tcpTransport.connect()
        }
    }

    /**
     * Connects the TCP transport to a specific [host]:[port].
     *
     * Wi-Fi mode:   host = desktop LAN IP (e.g. "192.168.1.42"), port = 4242
     * ADB mode:     host = "127.0.0.1", port = 4242
     *               (requires:  adb forward tcp:4242 tcp:4242  on the host machine)
     */
    fun reconnectTransport(host: String, port: Int) {
        Log.i(TAG, "[CMD] Transport connect → $host:$port")
        serviceScope.launch(Dispatchers.IO) {
            tcpTransport.disconnect()
            tcpTransport.host = host
            tcpTransport.port = port
            tcpTransport.connect()
        }
    }

    fun stopTransport() {
        Log.i(TAG, "[CMD] Transport stop")
        serviceScope.launch(Dispatchers.IO) {
            tcpTransport.disconnect()
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private fun handleStart() {
        ensureForeground()

        if (isRunning) {
            Log.i(TAG, "AsrService is already running, skipping internal start")
            return
        }

        if (cameraManager.isCameraProviderReady()) startInternal() else pendingStart = true
    }

    private fun ensureForeground() {
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startInternal() {
        _runtimeState.value = RuntimeState.CONNECTING
        benchmarkProcessor.reset(restartCount)
        moduleRegistry.startModule("battery")
        moduleRegistry.startModule("diagnostics")
        moduleRegistry.startModule("transport")
        moduleRegistry.startModule("imu")
        moduleRegistry.startModule("camera")
        moduleRegistry.startModule("gps")
        isRunning = true
        startHeartbeatLoop()
        Log.i(TAG, "[ASR] Runtime started (sensors active). DeviceId=$deviceId Commands=${commandDispatcher.registeredCommands()}")
    }

    private fun startHeartbeatLoop() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun handleStop() {
        Log.i(TAG, "[ASR] Stopping runtime modules & transport")
        moduleRegistry.stopAll()
        tcpTransport.disconnect()
        isRunning = false
        _runtimeState.value = RuntimeState.STOPPED
        _clientInfo.value = null
    }

    private fun handleRestart() {
        moduleRegistry.stopAll()
        restartCount++
        startInternal()
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
        Log.i(TAG, "[CMD] Camera config: ${activeCameraConfig.toLogString()}")
        if (cameraManager.state == org.asr.runtime.common.ModuleState.RUNNING) {
            cameraManager.stop()
            cameraManager.config = activeCameraConfig
            cameraManager.start()
        } else {
            cameraManager.config = activeCameraConfig
        }
    }

    private fun handleTcpConnect(intent: Intent) {
        // Extract extras immediately (before launching coroutine — intent extras may be recycled).
        val host = intent.getStringExtra("host") ?: "127.0.0.1"
        val port = intent.getIntExtra("port", 4242)
        Log.i(TAG, "[ADB] TCP connect → $host:$port")
        // Delegate to reconnectTransport() which runs on Dispatchers.IO.
        // Do NOT call tcpTransport.disconnect() here directly — it calls Thread.join()
        // which would block the main/binder thread for up to 1 second.
        reconnectTransport(host, port)
    }

    private fun handleDevMode(intent: Intent) {
        val flag  = intent.getStringExtra("flag")  ?: return
        val value = intent.getBooleanExtra("value", false)
        DevModeConfig.applyFlag(flag, value)
        Log.i(TAG, "[DEV] Flag '$flag' = $value")
    }

    private fun logStatus() {
        val s           = benchmarkProcessor.snapshot
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
            appendLine("Transport    : pkts=${transportManager.totalPacketsSent} drops=${transportManager.totalPacketDrops} crc=${transportManager.totalCrcFailures} q=${tcpTransport.queueDepth}")
            appendLine("Capabilities : $capabilities")
            appendLine()
            appendLine("[MODULE STATES]")
            moduleStatus.entries.forEach { appendLine("  ${it.key.padEnd(12)}: ${it.value}") }
            append("======================================")
        })
    }

    // ── Device ID ─────────────────────────────────────────────────────────────

    /**
     * Generates a stable device ID from a random UUID stored in SharedPreferences.
     * Survives reboots. Does not use any hardware identifiers.
     */
    private fun getOrCreateDeviceId(): Byte {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uuidStr = prefs.getString(PREF_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(PREF_DEVICE_ID, it).apply()
                Log.i(TAG, "[ASR] Generated new device ID UUID: $it")
            }
        // Truncate UUID hash to single byte (0-255)
        return (uuidStr.hashCode() and 0xFF).toByte()
    }

    // ── Android lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        try { unregisterReceiver(controlReceiver) } catch (e: Exception) { /* already unregistered */ }
        
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        
        moduleRegistry.releaseAll()
        Log.i(TAG, "[ASR] Runtime destroyed")
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ASR Runtime", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ASR Runtime Active")
            .setContentText("Transport: TCP | DeviceId: $deviceId")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
}
