package com.example.cameraros.camera

import androidx.camera.core.ImageProxy

/**
 * Interface for a component that processes camera frames.
 * Implementations can be benchmarks, ROS publishers, OpenCV filters, etc.
 */
interface FrameProcessor {
    /**
     * Called for every new frame available from the camera.
     * @param image The ImageProxy to process.
     * @param timestampNs The hardware timestamp of the frame in nanoseconds.
     */
    fun process(image: ImageProxy, timestampNs: Long)
}
