package com.example.cameraros.camera

import androidx.camera.core.ImageProxy

/**
 * Interface for a component that processes camera frames.
 * Implementations can be benchmarks, ROS publishers, OpenCV filters, etc.
 *
 * The [FrameMetadata] parameter carries all per-frame context (ID, timestamps).
 * Adding new metadata fields never requires changing this signature.
 *
 * Contract:
 * - Implementors MUST NOT hold a reference to [image] after [process] returns.
 * - [image] is closed by [CameraAnalyzer] — processors must not close it themselves.
 */
interface FrameProcessor {
    /**
     * Called for every new frame available from the camera.
     * @param image    The ImageProxy to process.
     * @param metadata Immutable per-frame metadata (ID, timestamps).
     */
    fun process(image: ImageProxy, metadata: FrameMetadata)
}
