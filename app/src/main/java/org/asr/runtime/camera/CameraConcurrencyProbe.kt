package org.asr.runtime.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as Camera2Manager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only test: opens several cameras at once and counts real frames, because what a phone
 * reports and what it actually does can differ. Stop the runtime camera before running it.
 */
object CameraConcurrencyProbe {
    private const val TAG = "AsrCameraProbe"

    @SuppressLint("MissingPermission")
    fun run(context: Context, ids: List<String>, seconds: Int) {
        val thread = HandlerThread("AsrCameraProbe").apply { start() }
        val handler = Handler(thread.looper)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
        val counts = ids.associateWith { AtomicInteger(0) }
        val devices = mutableListOf<CameraDevice>()
        val readers = mutableListOf<ImageReader>()
        ids.forEach { id ->
            val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
            readers += reader
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.let { image ->
                    counts.getValue(id).incrementAndGet()
                    image.close()
                }
            }, handler)
            runCatching {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        devices += device
                        Log.i(TAG, "camera $id opened")
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                request.addTarget(reader.surface)
                                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                session.setRepeatingRequest(request.build(), null, handler)
                                Log.i(TAG, "camera $id streaming")
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.w(TAG, "camera $id session failed")
                            }
                        }, handler)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        Log.w(TAG, "camera $id disconnected")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        Log.w(TAG, "camera $id error $error")
                    }
                }, handler)
            }.onFailure { Log.w(TAG, "camera $id open failed: ${it.message}") }
        }
        handler.postDelayed({
            val result = ids.joinToString { "$it=${counts.getValue(it).get() / seconds.coerceAtLeast(1)} FPS" }
            Log.i(TAG, "RESULT $result")
            devices.forEach { runCatching { it.close() } }
            readers.forEach { runCatching { it.close() } }
            thread.quitSafely()
        }, seconds * 1000L)
    }
}
