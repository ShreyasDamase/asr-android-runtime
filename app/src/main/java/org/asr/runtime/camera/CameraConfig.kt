package org.asr.runtime.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis

/**
 * Configuration for the camera pipeline.
 */
data class CameraConfig(
    val resolution: Size = Size(640, 480),
    val targetFps: Int = 30,
    val imageFormat: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
) {
    fun toLogString(): String =
        "resolution=${resolution.width}x${resolution.height} fps=$targetFps image_format=YUV_420_888"
}
