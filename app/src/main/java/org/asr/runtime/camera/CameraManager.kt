package org.asr.runtime.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import org.asr.runtime.diagnostic.BenchmarkProcessor
import org.asr.runtime.diagnostic.ThermalStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages CameraX operations for the robotics service.
 */
class CameraManager(
    private val context: Context,
    private val onCameraError: () -> Unit = {},
    private val onThermalStatus: (ThermalStatus) -> Unit = {},
    private val onCameraStarted: () -> Unit = {}
) : SensorModule {

    override val id: String = "camera"
    override var state: ModuleState = ModuleState.STOPPED

    // To be set by AsrService
    var lifecycleOwner: LifecycleOwner? = null
    var cameraPipeline: CameraPipeline? = null
    var benchmarkProcessor: BenchmarkProcessor? = null
    var config: CameraConfig = CameraConfig()

    override fun start() {
        val lo = lifecycleOwner ?: return
        val cp = cameraPipeline ?: return
        val bp = benchmarkProcessor ?: BenchmarkProcessor(context)
        startCamera(lo, cp, bp, config)
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
    private val cameraOperationGeneration = AtomicLong(0)
    // Written on the main executor (bind/unbind), read from binder & TCP threads.
    @Volatile private var imageAnalysis: ImageAnalysis? = null

    fun initialize(onReady: () -> Unit, onError: (Throwable) -> Unit = {}) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    onReady()
                } catch (error: Throwable) {
                    Log.e(TAG, "Camera provider initialization failed", error)
                    onError(error)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (error: Throwable) {
            Log.e(TAG, "Camera provider initialization could not start", error)
            onError(error)
        }
    }

    fun isCameraProviderReady(): Boolean = cameraProvider != null

    // Written on the main executor when the camera binds; read from the binder
    // thread (ACTION_FLASHLIGHT) and the TCP dispatch thread (flashlight.set).
    // Without @Volatile those threads can see a stale null and the torch command
    // silently no-ops even though the camera is bound.
    @Volatile private var camera: androidx.camera.core.Camera? = null

    fun setTorch(enable: Boolean): Boolean {
        return try {
            val cam = camera ?: return false
            cam.cameraControl.enableTorch(enable)
            Log.i(TAG, "Torch set to $enable")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch: ${e.message}")
            false
        }
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        cameraPipeline: CameraPipeline,
        benchmarkProcessor: BenchmarkProcessor,
        config: CameraConfig
    ) {
        val provider = cameraProvider ?: return
        val generation = cameraOperationGeneration.incrementAndGet()
        state = ModuleState.STARTING

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

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(config.imageFormat)
            .setTargetRotation(android.view.Surface.ROTATION_90)

        // Lock AE frame rate to targetFps via Camera2 interop
        Camera2Interop.Extender(analysisBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(config.targetFps, config.targetFps)
            )

        imageAnalysis = analysisBuilder.build()
            .also {
                it.setAnalyzer(cameraExecutor, CameraAnalyzer(cameraPipeline, benchmarkProcessor))
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(config.lensFacing)
            .build()

        val mainExecutor = ContextCompat.getMainExecutor(context)
        mainExecutor.execute {
            if (generation != cameraOperationGeneration.get()) return@execute
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
                Log.i(TAG, "Camera Started with lens facing ${config.lensFacing}")
                state = ModuleState.RUNNING
                onCameraStarted()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed for lens ${config.lensFacing}", exc)
                state = ModuleState.ERROR
                onCameraError()
            }
        }
    }

    fun stopCamera() {
        val generation = cameraOperationGeneration.incrementAndGet()
        state = ModuleState.STOPPING
        val mainExecutor = ContextCompat.getMainExecutor(context)
        mainExecutor.execute {
            if (generation != cameraOperationGeneration.get()) return@execute
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding camera: ${e.message}")
            }
            camera = null
            imageAnalysis = null
            Log.i(TAG, "Camera Stopped")
            state = ModuleState.STOPPED
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
