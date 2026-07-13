package com.example.cameraros.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Manages CameraX operations for the robotics service.
 *
 * Responsibilities:
 * - Initializes and caches camera characteristics (single query per session).
 * - Registers a thermal status listener to log thermal events (log-only, no auto-degradation).
 * - Builds the [ImageAnalysis] use case with robotics-optimized settings.
 * - Manages the camera analyzer thread lifetime.
 *
 * @param context          Application / service context.
 * @param onCameraError    Callback invoked on Camera2 device errors. Called from camera thread.
 * @param onThermalStatus  Callback invoked on thermal status changes. Called from main thread.
 */
class CameraManager(
    private val context: Context,
    private val onCameraError: () -> Unit = {},
    private val onThermalStatus: (ThermalStatus) -> Unit = {}
) {

    companion object {
        private const val TAG = "PhoneCamera"

        /**
         * Elevate the analyzer thread to THREAD_PRIORITY_VIDEO using the Linux-level
         * scheduler priority (maps to nice -10 on most kernels).
         *
         * Set to false if you observe scheduler starvation or OEM-specific issues.
         * THREAD_PRIORITY_VIDEO is safer than Thread.MAX_PRIORITY because it uses
         * Android's process priority system rather than the JVM scheduler.
         */
        private const val ENABLE_HIGH_PRIORITY_ANALYZER = true
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Cached camera characteristics — queried once in initialize(), reused everywhere.
    private var backCameraId: String = "0"
    private var cachedCharacteristics: CameraCharacteristics? = null

    /**
     * Single-threaded executor for the CameraX analyzer.
     * Thread priority is set from within the thread body — the only correct approach
     * for android.os.Process.setThreadPriority().
     */
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            if (ENABLE_HIGH_PRIORITY_ANALYZER) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_VIDEO)
            }
            r.run()
        }, "camera-analyzer").apply { isDaemon = true }
    }

    /**
     * Initializes the camera system.
     * Caches camera characteristics, registers the thermal listener, and prepares
     * the [ProcessCameraProvider]. [onReady] is called when the provider is available.
     */
    fun initialize(onReady: () -> Unit) {
        loadAndLogCameraCharacteristics()
        registerThermalListener()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            Log.i(TAG, "Camera System Ready")
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Returns true if the [ProcessCameraProvider] is ready to bind use cases. */
    fun isCameraProviderReady(): Boolean = cameraProvider != null

    /**
     * Starts the camera pipeline using the provided [config].
     *
     * Applies robotics-optimized Camera2 settings:
     * - Target FPS locked to [CameraConfig.targetFps]
     * - CONTROL_CAPTURE_INTENT_VIDEO_RECORD for continuous streaming optimization
     * - State callbacks for detailed camera lifecycle logging
     */
    fun startCamera(lifecycleOwner: LifecycleOwner, pipeline: FramePipeline, config: CameraConfig) {
        val provider = cameraProvider ?: run {
            Log.e(TAG, "startCamera called before camera provider was ready")
            return
        }

        Log.i(TAG, "Camera State: Camera Opening")

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    config.resolution,
                    // Prefer smaller resolutions on miss — avoids unexpected bandwidth increase.
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        val imageAnalysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(config.imageFormat)

        // Camera2 interop for robotics-specific tuning
        val ext = Camera2Interop.Extender(imageAnalysisBuilder)

        // Lifecycle state callbacks for Camera2 transitions
        ext.setDeviceStateCallback(object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.i(TAG, "Camera State: Camera Opened")
            }
            override fun onClosed(camera: CameraDevice) {
                Log.i(TAG, "Camera State: Camera Closed")
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera State: Camera Disconnected")
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera State: Camera Device Error: $error")
                onCameraError()
            }
        })

        ext.setSessionStateCallback(object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.i(TAG, "Camera State: Capture Session Created")
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Camera State: Capture Session Configuration Failed")
            }
        })

        // Lock FPS to eliminate the default 16 FPS CameraX auto-exposure limit
        val fpsRange = selectBestFpsRange(config.targetFps)
        if (fpsRange != null) {
            Log.i(TAG, "Configuring FPS range: [${fpsRange.lower}, ${fpsRange.upper}]")
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        } else {
            Log.w(TAG, "No suitable ${config.targetFps} FPS range found, using CameraX default")
        }

        // Hint to the ISP to optimize for continuous video streaming rather than still capture.
        // Can reduce frame jitter on Qualcomm SoCs.
        ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
        )

        imageAnalysis = imageAnalysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor, CameraAnalyzer(pipeline))
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
            Log.i(TAG, "Camera State: ImageAnalysis Started")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        Log.i(TAG, "Camera State: ImageAnalysis Stopped")
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Queries and caches the back camera characteristics.
     * Also logs the device and camera info block shown at startup.
     * Called once from [initialize] — never called again.
     */
    private fun loadAndLogCameraCharacteristics() {
        try {
            val camera2Manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager

            for (id in camera2Manager.cameraIdList) {
                val chars = camera2Manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                    backCameraId = id
                    cachedCharacteristics = chars
                    break
                }
            }

            if (cachedCharacteristics == null && camera2Manager.cameraIdList.isNotEmpty()) {
                backCameraId = camera2Manager.cameraIdList[0]
                cachedCharacteristics = camera2Manager.getCameraCharacteristics(backCameraId)
            }

            Log.i(TAG, buildDeviceLog())
            cachedCharacteristics?.let { Log.i(TAG, buildCameraLog(it)) }
                ?: Log.w(TAG, "Could not retrieve camera characteristics")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading camera characteristics", e)
        }
    }

    /**
     * Selects the best available FPS range for [targetFps].
     * Uses cached characteristics — no hardware query on every start.
     */
    private fun selectBestFpsRange(targetFps: Int): Range<Int>? {
        val chars = cachedCharacteristics ?: return null
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null

        return ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
            ?: ranges.firstOrNull { it.upper == targetFps }
            ?: ranges.firstOrNull { it.lower <= targetFps && it.upper >= targetFps }
            ?: ranges.maxByOrNull { it.upper }
    }

    /**
     * Registers a thermal status listener on API 29+.
     * Logs the state and delegates to [onThermalStatus] — no auto-degradation policy here.
     */
    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return
        powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context)) { androidStatus ->
            val status = ThermalStatus.fromAndroid(androidStatus)
            Log.w(TAG, "[THERMAL] status=${status.name}")
            onThermalStatus(status)
        }
    }

    private fun buildDeviceLog() = """
        
        ================ Device Info ================
        Device       : ${Build.MANUFACTURER} ${Build.MODEL}
        Android      : ${Build.VERSION.RELEASE}
        SDK          : ${Build.VERSION.SDK_INT}
        =============================================
    """.trimIndent()

    private fun buildCameraLog(chars: CameraCharacteristics): String {
        val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY  -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL    -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3       -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.joinToString(", ") { "[${it.lower}, ${it.upper}]" } ?: "NONE"
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        return """
            
            ============= Camera Characteristics =============
            Camera ID          : $backCameraId
            Lens               : BACK
            Hardware Level     : $hwLevel
            AE FPS Ranges      : $fpsRanges
            Sensor Orientation : $orientation°
            Supports RAW       : ${caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true}
            Supports Burst     : ${caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE) == true}
            Supports HiSpeed   : ${caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true}
            =============================================
        """.trimIndent()
    }
}
