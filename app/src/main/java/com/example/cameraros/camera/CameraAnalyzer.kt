package com.example.cameraros.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX Analyzer that dispatches frames to a pipeline.
 */
class CameraAnalyzer(
    private val pipeline: FramePipeline
) : ImageAnalysis.Analyzer {

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val timestampNs = image.imageInfo.timestamp

            // Dispatch to the robotics pipeline
            pipeline.dispatch(image, timestampNs)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // CRITICAL: Must close every ImageProxy immediately
            image.close()
        }
    }
}
