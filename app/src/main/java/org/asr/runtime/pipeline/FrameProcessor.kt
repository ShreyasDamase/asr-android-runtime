package org.asr.runtime.pipeline

import androidx.camera.core.ImageProxy

/**
 * Interface for a component that processes camera frames.
 */
interface FrameProcessor {
    /**
     * Called for every new frame available from the camera.
     * @param image The ImageProxy to process.
     * @param metadata Timing and ID information for the frame.
     */
    fun process(image: ImageProxy, metadata: FrameMetadata)
}
