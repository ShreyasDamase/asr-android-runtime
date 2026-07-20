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
import android.os.IBinder
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
import org.asr.runtime.diagnostic.BenchmarkProcessor
import org.asr.runtime.diagnostic.DiagnosticsManager
import org.asr.runtime.pipeline.FramePipeline
import org.asr.runtime.sensor.ImuManager
import org.asr.runtime.sensor.EnvironmentManager
import org.asr.runtime.sensor.MotionManager
import org.asr.runtime.transport.TransportManager
import org.asr.runtime.transport.LogTransport
import java.util.Locale

/**
 * Foreground Service that owns and orchestrates the robotics sensor runtime.
 */
class AsrService : LifecycleService() {

    companion object {
        private const val TAG = "PhoneCamera"
        private const val CHANNEL_ID = "AsrRuntimeService"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START   = "org.asr.runtime.START"
        const val ACTION_STOP    = "org.asr.runtime.STOP"
        const val ACTION_RESTART = "org.asr.runtime.RESTART"
        const val ACTION_STATUS  = "org.asr.runtime.STATUS"
        const val ACTION_SPEAK   = "org.asr.runtime.SPEAK"
        const val ACTION_MODULE_START = "org.asr.runtime.MODULE_START"
        const val ACTION_MODULE_STOP  = "org.asr.runtime.MODULE_STOP"
        const val ACTION_PROFILE_ENABLE = "org.asr.runtime.PROFILE_ENABLE"

        val PROFILES = mapOf(
            "mapping" to listOf("camera", "imu"),
            "teleop" to listOf("camera", "audio", "speaker"),
            "navigation" to listOf("imu", "battery")
        )
    }

    // Managers
    private lateinit var cameraManager: CameraManager
    private lateinit var imuManager: ImuManager
    private lateinit var environmentManager: EnvironmentManager
    private lateinit var motionManager: MotionManager
    private lateinit var audioRuntime: AudioRuntime
    private lateinit var batteryManager: BatteryManager
    private lateinit var speakerManager: SpeakerManager
    private lateinit var transportManager: TransportManager
    private lateinit var diagnosticsManager: DiagnosticsManager
    private lateinit var capabilityManager: CapabilityManager
    
    // Core Logic
    private lateinit var benchmarkProcessor: BenchmarkProcessor
    private lateinit var pipeline: FramePipeline
    private lateinit var moduleRegistry: ModuleRegistry

    private var activeCameraConfig = CameraConfig()
    private var isRunning = false
    private var restartCount = 0
    private var pendingStart = false

    private val binder = LocalBinder()

    fun getModuleStatus(): Map<String, String> = moduleRegistry.getModuleStatus()

    inner class LocalBinder : Binder() {
        fun getService(): AsrService = this@AsrService
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STATUS) logStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[ASR] Starting Runtime v${BuildConfig.VERSION_NAME}")

        // 1. Initialize Pipeline & Diagnostics
        benchmarkProcessor = BenchmarkProcessor(this)
        diagnosticsManager = DiagnosticsManager(this)
        pipeline = FramePipeline(processors = listOf(benchmarkProcessor))

        // 2. Initialize Hardware Managers
        cameraManager = CameraManager(
            context = this,
            onCameraError = { benchmarkProcessor.onCameraError() },
            onThermalStatus = { status ->
                Log.w(TAG, "[THERMAL] status=${status.name}")
                benchmarkProcessor.updateThermalStatus(status)
            }
        ).apply {
            this.lifecycleOwner = this@AsrService
            this.pipeline = this@AsrService.pipeline
            this.config = activeCameraConfig
        }

        imuManager = ImuManager(this)
        environmentManager = EnvironmentManager(this)
        motionManager = MotionManager(this)
        audioRuntime = AudioRuntime(this)
        batteryManager = BatteryManager(this)
        speakerManager = SpeakerManager(this)
        capabilityManager = CapabilityManager(this)

        // 3. Initialize Transport
        transportManager = TransportManager().apply {
            addTransport(LogTransport()) // Default: data to Logcat
        }

        // 4. Registry for centralized control
        moduleRegistry = ModuleRegistry(listOf(
            cameraManager,
            imuManager,
            environmentManager,
            motionManager,
            audioRuntime,
            batteryManager,
            speakerManager,
            transportManager,
            diagnosticsManager
        ))

        cameraManager.initialize(onReady = {
            if (pendingStart) {
                pendingStart = false
                startInternal()
            }
        })

        // 5. External Control (Broadcasts)
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START   -> handleStart()
            ACTION_STOP    -> handleStop()
            ACTION_RESTART -> handleRestart()
            ACTION_SPEAK   -> intent.getStringExtra("text")?.let { speakerManager.speak(it) }
            ACTION_MODULE_START -> intent.getStringExtra("module_id")?.let { 
                Log.i(TAG, "[CMD] Starting module: $it")
                moduleRegistry.startModule(it) 
            }
            ACTION_MODULE_STOP -> intent.getStringExtra("module_id")?.let { 
                Log.i(TAG, "[CMD] Stopping module: $it")
                moduleRegistry.stopModule(it) 
            }
            ACTION_PROFILE_ENABLE -> intent.getStringExtra("profile")?.let { profileId ->
                PROFILES[profileId]?.forEach { modId ->
                    Log.i(TAG, "[CMD] Profile '$profileId' enabling: $modId")
                    moduleRegistry.startModule(modId)
                }
            }
        }
        return START_STICKY
    }

    private fun handleStart() {
        if (isRunning) return

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (cameraManager.isCameraProviderReady()) {
            startInternal()
        } else {
            pendingStart = true
        }
    }

    private fun startInternal() {
        benchmarkProcessor.reset(restartCount)
        
        // Demand-driven: Only start "Core" modules by default
        moduleRegistry.startModule("battery")
        moduleRegistry.startModule("diagnostics")
        
        isRunning = true
        
        // Start Heartbeat loop
        startHeartbeatLoop()
        
        Log.i(TAG, "ASR Runtime Started (Demand-Driven Mode)")
    }

    private fun startHeartbeatLoop() {
        // Simple polling for diagnostics log
        val handler = android.os.Handler(mainLooper)
        handler.post(object : Runnable {
            override fun run() {
                if (isRunning) {
                    val s = benchmarkProcessor.snapshot
                    diagnosticsManager.logHeartbeat(
                        fps = s.instantFps,
                        latency = s.latencyMs,
                        activeModules = moduleRegistry.getRunningModules(),
                        transportConnected = true // LogTransport is always connected
                    )
                    handler.postDelayed(this, 1000)
                }
            }
        })
    }

    private fun handleStop() {
        moduleRegistry.stopAll()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleRestart() {
        moduleRegistry.stopAll()
        restartCount++
        startInternal()
    }

    private fun logStatus() {
        val s = benchmarkProcessor.snapshot
        val audioSnapshot = audioRuntime.snapshot
        val capabilities = capabilityManager.discover()
        val moduleStatus = moduleRegistry.getModuleStatus()
        
        Log.i(TAG, """
            
            ============= ASR Status =============
            Running      : ${if (isRunning) "YES" else "NO"}
            FPS (instant): ${String.format(Locale.US, "%.1f", s.instantFps)}
            Latency      : ${String.format(Locale.US, "%.1f", s.latencyMs)} ms
            Battery      : ${String.format(Locale.US, "%.1f", batteryManager.level)}%, ${batteryManager.temperature}°C
            Microphone   : ${if (audioSnapshot.isRunning) "ON" else "OFF"}, dbfs=${String.format(Locale.US, "%.1f", audioSnapshot.dbfs)}
            Uptime       : ${s.uptimeMs / 1000}s
            Capabilities : $capabilities
            
            [MODULE STATES]
            ${moduleStatus.entries.joinToString("\n") { "${it.key.padEnd(12)}: ${it.value}" }}
            ======================================
        """.trimIndent())
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
        moduleRegistry.releaseAll()
        Log.i(TAG, "ASR Runtime Destroyed")
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
}
