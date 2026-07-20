package org.asr.runtime.pipeline

import androidx.camera.core.ImageProxy

/**
 * Orchestrates a sequence of FrameProcessors.
 */
class FramePipeline(
    private val processors: List<FrameProcessor>,
    private val onFrameGap: (frameId: Long, gapMs: Long) -> Unit = { _, _ -> }
) {
    fun dispatch(image: ImageProxy, metadata: FrameMetadata) {
        processors.forEach { processor ->
            try {
                processor.process(image, metadata)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
