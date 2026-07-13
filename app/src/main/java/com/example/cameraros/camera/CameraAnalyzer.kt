package com.example.cameraros.camera

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX Analyzer that captures per-frame entry timestamps and dispatches
 * frames to the [FramePipeline].
 *
 * Timing captured here:
 *   analyzerEnterNs  — System.nanoTime() at the top of analyze(), before any work.
 *   sensorTimestampNs — Hardware capture timestamp from image.imageInfo.timestamp.
 *
 * The pipeline uses these to construct a [FrameMetadata] for each frame.
 * [image] is always closed in the finally block — processors must not close it.
 */
class CameraAnalyzer(
    private val pipeline: FramePipeline
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "PhoneCamera"
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        // Capture entry timestamp immediately — before any other work.
        val analyzerEnterNs = System.nanoTime()
        val sensorTimestampNs = image.imageInfo.timestamp

        try {
            pipeline.dispatch(image, sensorTimestampNs, analyzerEnterNs)
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline dispatch failed", e)
        } finally {
            // CRITICAL: Must close every ImageProxy — processors must not close it themselves.
            image.close()
        }
    }
}
