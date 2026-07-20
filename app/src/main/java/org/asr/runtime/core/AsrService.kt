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
import org.asr.runtime.battery.BatteryManager
import org.asr.runtime.camera.CameraConfig
import org.asr.runtime.camera.CameraManager
import org.asr.runtime.diagnostic.BenchmarkProcessor
import org.asr.runtime.diagnostic.ThermalStatus
import org.asr.runtime.pipeline.FramePipeline
import org.asr.runtime.sensor.AndroidSensorRuntime
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
    }

    private lateinit var benchmarkProcessor: BenchmarkProcessor
    private lateinit var pipeline: FramePipeline
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorRuntime: AndroidSensorRuntime
    private lateinit var audioRuntime: AudioRuntime
    private lateinit var batteryManager: BatteryManager
    private lateinit var capabilityManager: CapabilityManager
    private lateinit var moduleRegistry: ModuleRegistry

    private var activeCameraConfig = CameraConfig()
    private var isRunning = false
    private var restartCount = 0
    private var pendingStart = false

    private val binder = LocalBinder()

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

        benchmarkProcessor = BenchmarkProcessor(this)

        pipeline = FramePipeline(
            processors = listOf(benchmarkProcessor)
        )

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

        sensorRuntime = AndroidSensorRuntime(this)
        audioRuntime = AudioRuntime(this)
        batteryManager = BatteryManager(this)
        capabilityManager = CapabilityManager(this)

        moduleRegistry = ModuleRegistry(listOf(
            cameraManager,
            sensorRuntime,
            audioRuntime,
            batteryManager
        ))

        cameraManager.initialize(onReady = {
            if (pendingStart) {
                pendingStart = false
                startInternal()
            }
        })

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
        moduleRegistry.startAll()
        isRunning = true
        Log.i(TAG, "ASR Runtime Started")
    }

    private fun handleStop() {
        moduleRegistry.stopAll()
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun handleRestart() {
        moduleRegistry.stopAll()
        restartCount++
        startInternal()
    }

    private fun logStatus() {
        val s = benchmarkProcessor.snapshot
        val sensorSnapshot = sensorRuntime.snapshot
        val audioSnapshot = audioRuntime.snapshot
        val capabilities = capabilityManager.discover()
        Log.i(TAG, """
            
            ============= ASR Status =============
            Running      : ${if (isRunning) "YES" else "NO"}
            FPS (instant): ${String.format(Locale.US, "%.1f", s.instantFps)}
            FPS (average): ${String.format(Locale.US, "%.1f", s.averageFps)}
            Latency      : ${String.format(Locale.US, "%.1f", s.latencyMs)} ms
            Frames       : ${s.totalFrames}
            Sensors      : ${sensorSnapshot.activeSensors}/${sensorSnapshot.availableSensors} active, events=${sensorSnapshot.totalEvents}
            Last Sensor  : ${sensorSnapshot.lastSensorName.ifBlank { "n/a" }} (${sensorSnapshot.lastSensorType.ifBlank { "n/a" }})
            Microphone   : ${if (audioSnapshot.isRunning) "ON" else "OFF"}, buffers=${audioSnapshot.buffersRead}, dbfs=${String.format(Locale.US, "%.1f", audioSnapshot.dbfs)}
            Battery      : ${String.format(Locale.US, "%.1f", batteryManager.level)}%, ${if (batteryManager.isCharging) "Charging" else "Discharging"}, ${batteryManager.temperature}°C
            Memory       : ${s.memoryMb} MB
            Uptime       : ${s.uptimeMs / 1000}s
            Capabilities : $capabilities
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
