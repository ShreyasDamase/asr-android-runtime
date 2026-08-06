package org.asr.runtime.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.asr.runtime.diagnostic.BenchmarkProcessor

/**
 * CameraX Analyzer that dispatches frames through the CameraPipeline.
 *
 * Responsibilities:
 * - Build [FrameMetadata] with frame ID and nanosecond timestamps
 * - Forward to [CameraPipeline] for compression and publishing
 * - Forward to [BenchmarkProcessor] for FPS/latency tracking
 * - Always close [ImageProxy] in finally block to prevent frame stalls
 */
class CameraAnalyzer(
    private val cameraPipeline: CameraPipeline,
    private val benchmarkProcessor: BenchmarkProcessor
) : ImageAnalysis.Analyzer {

    private var frameCounter = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val analyzerEnterNs   = System.nanoTime()
            val sensorTimestampNs = image.imageInfo.timestamp

            val metadata = FrameMetadata(
                frameId           = ++frameCounter,
                sensorTimestampNs = sensorTimestampNs,
                analyzerEnterNs   = analyzerEnterNs
            )

            // Benchmark first (lightweight counter update)
            benchmarkProcessor.process(image, metadata)

            // Camera pipeline: compress + packetize + publish
            cameraPipeline.process(image, metadata)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}
