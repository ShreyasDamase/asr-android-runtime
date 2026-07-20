package org.asr.runtime.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.asr.runtime.pipeline.FrameMetadata
import org.asr.runtime.pipeline.FramePipeline

/**
 * CameraX Analyzer that dispatches frames to a pipeline.
 */
class CameraAnalyzer(
    private val pipeline: FramePipeline
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val analyzerEnterNs = System.nanoTime()
            val sensorTimestampNs = image.imageInfo.timestamp

            val metadata = FrameMetadata(
                frameId = ++frameCounter,
                sensorTimestampNs = sensorTimestampNs,
                analyzerEnterNs = analyzerEnterNs
            )

            pipeline.dispatch(image, metadata)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}
