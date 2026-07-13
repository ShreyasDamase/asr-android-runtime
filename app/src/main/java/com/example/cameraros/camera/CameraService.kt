package com.example.cameraros.camera

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
import com.example.cameraros.BuildConfig
import com.example.cameraros.R
import java.util.Locale

/**
 * Foreground Service that owns and orchestrates the robotics camera pipeline.
 *
 * Lifecycle is controlled entirely via ADB intent commands — no UI required:
 *
 *   adb shell am start-foreground-service -n com.example.cameraros/.camera.CameraService \
 *       -a com.example.cameraros.START
 *
 *   adb shell am start-foreground-service ... -a com.example.cameraros.STOP
 *   adb shell am start-foreground-service ... -a com.example.cameraros.RESTART
 *   adb shell am broadcast          ... -a com.example.cameraros.STATUS
 *
 * Implements [com.example.cameraros.common.SensorModule] contract informally —
 * will be refactored to the interface when the module registry is introduced.
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "PhoneCamera"
        private const val CHANNEL_ID = "RoboticsCameraService"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START   = "com.example.cameraros.START"
        const val ACTION_STOP    = "com.example.cameraros.STOP"
        const val ACTION_RESTART = "com.example.cameraros.RESTART"
        const val ACTION_STATUS  = "com.example.cameraros.STATUS"
    }

    // -------------------------------------------------------------------------
    // Pipeline components
    // -------------------------------------------------------------------------

    private lateinit var benchmarkManager: BenchmarkManager
    private lateinit var pipeline: FramePipeline
    private lateinit var cameraManager: CameraManager

    /** Active camera configuration. Replaced on RESTART with a new config if needed. */
    private var activeConfig = CameraConfig()

    private var isCameraRunning = false
    private var restartCount = 0

    /**
     * Set to true when ACTION_START arrives before the camera provider is ready.
     * [CameraManager.initialize]'s onReady callback checks this and starts the camera.
     */
    private var pendingStart = false

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    // -------------------------------------------------------------------------
    // Status broadcast receiver
    // -------------------------------------------------------------------------

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STATUS) logStatus()
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        // Log build information once at startup
        Log.i(TAG, "[BUILD] version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        // Warn about MIUI battery optimizations — log only, no automatic exemption request.
        // Follow the README manual setup steps for reliable 24/7 operation on Xiaomi devices.
        if (Build.MANUFACTURER.lowercase() == "xiaomi") {
            Log.w(TAG, """
                [MIUI WARNING] Xiaomi device detected.
                Standard battery optimization exemption may be insufficient.
                For 24/7 operation, manually configure in MIUI Settings:
                  Settings → Apps → cameraros → Battery saver → No restrictions
                See README section "Xiaomi / MIUI Setup" for full instructions.
            """.trimIndent())
        }

        // Assemble the pipeline
        benchmarkManager = BenchmarkManager(this)

        pipeline = FramePipeline(
            processors = listOf(benchmarkManager),
            onFrameGap = { frameId, gapMs ->
                Log.w(TAG, "[GAP] frame_id=$frameId gap_ms=$gapMs (heuristic)")
                benchmarkManager.onFrameGap()
            }
        )

        cameraManager = CameraManager(
            context = this,
            onCameraError = { benchmarkManager.onCameraError() },
            onThermalStatus = { status ->
                Log.w(TAG, "[THERMAL] status=${status.name}")
                benchmarkManager.updateThermalStatus(status)
            }
        )

        cameraManager.initialize(onReady = {
            // If startStream() was called before the provider was ready, complete it now.
            if (pendingStart) {
                pendingStart = false
                cameraManager.startCamera(this, pipeline, activeConfig)
                isCameraRunning = true
                logActiveConfig()
            }
        })

        // Register STATUS broadcast receiver
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }

        Log.i(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START   -> startStream()
            ACTION_STOP    -> stopStream()
            ACTION_RESTART -> restartStream()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        cameraManager.shutdown()
        Log.i(TAG, "Service Destroyed")
    }

    // -------------------------------------------------------------------------
    // Stream control
    // -------------------------------------------------------------------------

    private fun startStream() {
        // Double-start guard — prevents accidental disruption from duplicate intents
        if (isCameraRunning) {
            Log.w(TAG, "START ignored — camera already running")
            return
        }

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        benchmarkManager.reset(restartCount)

        if (cameraManager.isCameraProviderReady()) {
            // Provider already available — start immediately
            cameraManager.startCamera(this, pipeline, activeConfig)
            isCameraRunning = true
            logActiveConfig()
        } else {
            // Provider still initializing — startCamera will be called from onReady
            Log.i(TAG, "Camera provider not ready yet, queuing start...")
            pendingStart = true
        }
    }

    private fun stopStream() {
        cameraManager.stopCamera()
        val s = benchmarkManager.snapshot
        Log.i(TAG, "Camera Stopped")
        Log.i(TAG, "Total Frames : ${s.totalFrames}")
        Log.i(TAG, "Average FPS  : ${String.format(Locale.US, "%.1f", s.averageFps)}")
        Log.i(TAG, "Runtime      : ${benchmarkManager.getRunningTimeMs() / 1000}s")
        Log.i(TAG, "Gap Events   : ${s.frameGapEvents}")
        Log.i(TAG, "Camera Errors: ${s.cameraErrors}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isCameraRunning = false
        stopSelf()
    }

    private fun restartStream() {
        Log.i(TAG, "Restarting camera stream...")
        if (isCameraRunning) {
            cameraManager.stopCamera()
            isCameraRunning = false
        }
        restartCount++
        benchmarkManager.reset(restartCount)
        cameraManager.startCamera(this, pipeline, activeConfig)
        isCameraRunning = true
        logActiveConfig()
        Log.i(TAG, "Camera stream restarted (restart #$restartCount)")
    }

    // -------------------------------------------------------------------------
    // Status & config logging
    // -------------------------------------------------------------------------

    private fun logStatus() {
        val s = benchmarkManager.snapshot  // single volatile read — thread-safe
        Log.i(TAG, """
            
            ============= Camera Status =============
            Running      : ${if (isCameraRunning) "YES" else "NO"}
            FPS (instant): ${String.format(Locale.US, "%.1f", s.instantFps)}
            FPS (average): ${String.format(Locale.US, "%.1f", s.averageFps)}
            Latency      : ${String.format(Locale.US, "%.1f", s.latencyMs)} ms
            Frames       : ${s.totalFrames}
            Gap Events   : ${s.frameGapEvents}
            Errors       : ${s.cameraErrors}
            Restarts     : ${s.restartCount}
            Thermal      : ${s.thermalStatus.name}
            Memory       : ${s.memoryMb} MB
            Resolution   : ${activeConfig.resolution.width}x${activeConfig.resolution.height}
            FPS Target   : ${activeConfig.targetFps}
            Processor    : BenchmarkProcessor
            =========================================
        """.trimIndent())
    }

    /** Logs the active camera configuration whenever the camera starts or restarts. */
    private fun logActiveConfig() {
        Log.i(TAG, "[CONFIG] ${activeConfig.toLogString()} processor=BenchmarkProcessor")
    }

    fun getBenchmarkManager() = benchmarkManager

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Robotics Camera Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Robotics Camera Active")
            .setContentText("Continuous frame capture running...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
}
