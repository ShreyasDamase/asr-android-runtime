package com.example.cameraros.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX operations for the robotics service.
 * Handles binding use cases and managing the background executor.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "PhoneCamera"
        private val TARGET_RESOLUTION = Size(640, 480)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var imageAnalysis: ImageAnalysis? = null

    fun initialize(onReady: () -> Unit) {
        logDeviceAndCameraInfo()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        pipeline: FramePipeline
    ) {
        val provider = cameraProvider ?: return

        Log.i(TAG, "Starting Camera...")

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    TARGET_RESOLUTION,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // ImageAnalysis (Robotics Core)
        val imageAnalysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        // Camera2 Interop Config
        val ext = Camera2Interop.Extender(imageAnalysisBuilder)

        // Set device state callback for debugging Camera2 transitions
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
            }
        })

        // Set session state callback for capture session debugging
        ext.setSessionStateCallback(object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.i(TAG, "Camera State: Capture Session Created")
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Camera State: Capture Session Configuration Failed")
            }
        })

        // Configure target FPS to override the default 16 FPS limit
        val targetFpsRange = selectBest30FpsRange()
        if (targetFpsRange != null) {
            Log.i(TAG, "Configuring Camera2 Target FPS Range to: [${targetFpsRange.lower}, ${targetFpsRange.upper}]")
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
        } else {
            Log.w(TAG, "No suitable 30 FPS range found, letting CameraX default")
        }

        imageAnalysis = imageAnalysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor, CameraAnalyzer(pipeline))
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            Log.i(TAG, "Camera State: Camera Opening")
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            Log.i(TAG, "Camera State: ImageAnalysis Started")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        Log.i(TAG, "Camera State: ImageAnalysis Stopped")
        Log.i(TAG, "Camera Stopped")
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    private fun logDeviceAndCameraInfo() {
        try {
            val camera2Manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
            val cameraIdList = camera2Manager.cameraIdList
            var selectedCameraId = "0"
            var characteristics: CameraCharacteristics? = null

            for (id in cameraIdList) {
                val chars = camera2Manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraMetadata.LENS_FACING_BACK) {
                    selectedCameraId = id
                    characteristics = chars
                    break
                }
            }

            if (characteristics == null && cameraIdList.isNotEmpty()) {
                selectedCameraId = cameraIdList[0]
                characteristics = camera2Manager.getCameraCharacteristics(selectedCameraId)
            }

            val deviceLog = """
                
                ================ Device Info ================
                Device       : ${Build.MANUFACTURER} ${Build.MODEL}
                Android      : ${Build.VERSION.RELEASE}
                SDK          : ${Build.VERSION.SDK_INT}
                =============================================
            """.trimIndent()
            Log.i(TAG, deviceLog)

            if (characteristics != null) {
                val hwLevelVal = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val hwLevelStr = when (hwLevelVal) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN ($hwLevelVal)"
                }

                val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val fpsRangesStr = fpsRanges?.joinToString(separator = ", ") { "[${it.lower}, ${it.upper}]" } ?: "NONE"

                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1

                val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val supportsRaw = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                val supportsBurst = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE) == true
                val supportsHighSpeed = capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true

                val cameraLog = """
                    
                    ============= Camera Characteristics =============
                    Camera ID          : $selectedCameraId
                    Lens               : BACK
                    Hardware Level     : $hwLevelStr
                    Max FPS Ranges     : $fpsRangesStr
                    Sensor Orientation : $sensorOrientation°
                    Supports RAW       : ${if (supportsRaw) "YES" else "NO"}
                    Supports Burst     : ${if (supportsBurst) "YES" else "NO"}
                    Supports High Speed: ${if (supportsHighSpeed) "YES" else "NO"}
                    Resolution         : ${TARGET_RESOLUTION.width}x${TARGET_RESOLUTION.height}
                    Image Format       : YUV_420_888
                    =============================================
                """.trimIndent()
                Log.i(TAG, cameraLog)
            } else {
                Log.w(TAG, "Could not retrieve camera characteristics")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging device and camera info", e)
        }
    }

    private fun selectBest30FpsRange(): Range<Int>? {
        try {
            val camera2Manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
            val cameraIdList = camera2Manager.cameraIdList
            var backCameraId = "0"
            for (id in cameraIdList) {
                val chars = camera2Manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                    backCameraId = id
                    break
                }
            }
            val characteristics = camera2Manager.getCameraCharacteristics(backCameraId)
            val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
            
            // Prefer [30, 30]
            val exact30 = fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
            if (exact30 != null) return exact30
            
            // Fallback to [15, 30] or any range with upper == 30
            val upper30 = fpsRanges.firstOrNull { it.upper == 30 }
            if (upper30 != null) return upper30
            
            // Fallback to any range containing 30
            val contains30 = fpsRanges.firstOrNull { it.lower <= 30 && it.upper >= 30 }
            if (contains30 != null) return contains30
            
            return fpsRanges.maxByOrNull { it.upper }
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting target FPS range", e)
            return null
        }
    }
}
