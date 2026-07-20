package org.asr.runtime.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.asr.runtime.common.SensorModule
import org.asr.runtime.diagnostic.ThermalStatus
import org.asr.runtime.pipeline.FramePipeline
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX operations for the robotics service.
 */
class CameraManager(
    private val context: Context,
    private val onCameraError: () -> Unit = {},
    private val onThermalStatus: (ThermalStatus) -> Unit = {}
) : SensorModule {

    override val id: String = "camera"

    // To be set by AsrService
    var lifecycleOwner: LifecycleOwner? = null
    var pipeline: FramePipeline? = null
    var config: CameraConfig = CameraConfig()

    override fun start() {
        val lo = lifecycleOwner ?: return
        val p = pipeline ?: return
        startCamera(lo, p, config)
    }

    override fun stop() {
        stopCamera()
    }

    override fun release() {
        shutdown()
    }

    companion object {
        private const val TAG = "PhoneCamera"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageAnalysis: ImageAnalysis? = null

    fun initialize(onReady: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun isCameraProviderReady(): Boolean = cameraProvider != null

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        pipeline: FramePipeline,
        config: CameraConfig
    ) {
        val provider = cameraProvider ?: return

        Log.i(TAG, "Starting Camera...")
        Log.i(TAG, "Config: ${config.toLogString()}")

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    config.resolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(config.imageFormat)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, CameraAnalyzer(pipeline))
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            Log.i(TAG, "Camera Started")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            onCameraError()
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        Log.i(TAG, "Camera Stopped")
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
