package com.example.cameraros.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis

/**
 * Configuration for the camera pipeline.
 *
 * Owned by [CameraService] and passed to [CameraManager.startCamera].
 * Changing this object requires restarting the camera pipeline.
 *
 * Future runtime configuration (e.g. "camera fps 15") replaces this
 * object and triggers a restart — no other code needs to change.
 */
data class CameraConfig(
    val resolution: Size = Size(640, 480),
    val targetFps: Int = 30,
    val imageFormat: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
) {
    /** Human-readable string for logs and telemetry. */
    fun toLogString(): String =
        "resolution=${resolution.width}x${resolution.height} fps=$targetFps image_format=YUV_420_888"
}
