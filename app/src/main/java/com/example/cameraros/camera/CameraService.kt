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
import com.example.cameraros.R
import java.util.Locale

/**
 * Foreground Service that runs the robotics camera pipeline.
 * Keeps the camera alive even when the UI is gone.
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "PhoneCamera"
        private const val CHANNEL_ID = "RoboticsCameraService"
        private const val NOTIFICATION_ID = 101
        
        const val ACTION_START = "com.example.cameraros.START"
        const val ACTION_STOP = "com.example.cameraros.STOP"
        const val ACTION_STATUS = "com.example.cameraros.STATUS"
    }

    private lateinit var cameraManager: CameraManager
    private lateinit var benchmarkManager: BenchmarkManager
    private lateinit var pipeline: FramePipeline

    private var isCameraRunning = false

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STATUS) {
                logStatus()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service Created")
        
        benchmarkManager = BenchmarkManager(this)
        pipeline = FramePipeline(listOf(benchmarkManager))
        cameraManager = CameraManager(this)
        
        cameraManager.initialize {
            Log.i(TAG, "Camera System Ready")
        }

        // Dynamically register custom status command broadcast receiver
        val filter = IntentFilter(ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopForegroundService()
        }
        
        return START_STICKY
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        benchmarkManager.reset()
        cameraManager.startCamera(this, pipeline)
        isCameraRunning = true
    }

    private fun stopForegroundService() {
        cameraManager.stopCamera()
        val runtime = benchmarkManager.getRunningTimeMs() / 1000
        Log.i(TAG, "Camera Stopped")
        Log.i(TAG, "Total Frames: ${benchmarkManager.totalFrames}")
        Log.i(TAG, "Average FPS: ${String.format(Locale.US, "%.1f", benchmarkManager.averageFps)}")
        Log.i(TAG, "Runtime: ${runtime}s")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        isCameraRunning = false
        stopSelf()
    }

    private fun logStatus() {
        val statusLog = """
            
            ============= Camera Status =============
            Running    : ${if (isCameraRunning) "YES" else "NO"}
            FPS        : ${String.format(Locale.US, "%.1f", benchmarkManager.currentFps)}
            Frames     : ${benchmarkManager.totalFrames}
            Memory     : ${benchmarkManager.getMemoryUsage()} MB
            Resolution : 640x480
            Processor  : Benchmark
            =========================================
        """.trimIndent()
        Log.i(TAG, statusLog)
    }

    fun getBenchmarkManager() = benchmarkManager

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        cameraManager.shutdown()
        Log.i(TAG, "Service Destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Robotics Camera Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Robotics Camera Active")
            .setContentText("Continuous frame capture running...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
