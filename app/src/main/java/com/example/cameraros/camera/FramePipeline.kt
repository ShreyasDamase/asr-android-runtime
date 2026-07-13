package com.example.cameraros.camera

import androidx.camera.core.ImageProxy

/**
 * Orchestrates a sequence of FrameProcessors.
 * This allows multiple "plugins" (Benchmark, ROS, CV) to consume the same camera stream.
 */
class FramePipeline(private val processors: List<FrameProcessor>) {

    /**
     * Dispatches the frame to all registered processors.
     * Note: Processors must not hold onto the image after their process() call returns
     * unless they manage their own reference counting.
     */
    fun dispatch(image: ImageProxy, timestampNs: Long) {
        processors.forEach { processor ->
            try {
                processor.process(image, timestampNs)
            } catch (e: Exception) {
                // Prevent one failing processor from killing the whole pipeline
                e.printStackTrace()
            }
        }
    }
}
