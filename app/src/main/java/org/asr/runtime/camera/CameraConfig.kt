package org.asr.runtime.camera

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis

/**
 * Configuration for the camera pipeline.
 */
data class CameraConfig(
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val resolution: Size = Size(640, 480),
    val targetFps: Int = 30,
    val imageFormat: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
) {
    fun toLogString(): String =
        "lens=${if (lensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"} " +
        "resolution=${resolution.width}x${resolution.height} fps=$targetFps"
}
