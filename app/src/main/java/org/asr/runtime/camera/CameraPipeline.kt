package org.asr.runtime.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import org.asr.runtime.core.DevModeConfig
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.CameraEncoding
import org.asr.runtime.protocol.PacketSerializer
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.transport.TransportManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compression stage interface — the only part of CameraPipeline that varies.
 *
 * Adding H.264 in v1.1 means implementing this interface and passing it to
 * [CameraPipeline] — no other pipeline code changes.
 */
interface CameraCompressor {
    val encoding: CameraEncoding
    /** Compress [image] into a byte array. Called from the camera executor thread. */
    fun compress(image: ImageProxy): ByteArray
}

/**
 * JPEG compressor — v1.0 default.
 *
 * Converts YUV_420_888 ImageProxy to JPEG via Android's YuvImage API.
 */
class JpegCompressor(val quality: Int = 85) : CameraCompressor {
    override val encoding = CameraEncoding.JPEG

    override fun compress(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }
}

/**
 * Raw YUV compressor — copies the Y plane only for minimal processing.
 * For full YUV420 raw streaming, use USB with adequate bandwidth.
 */
class RawYuvCompressor : CameraCompressor {
    override val encoding = CameraEncoding.RAW_YUV

    override fun compress(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val data = ByteArray(yBuffer.remaining())
        yBuffer.get(data)
        return data
    }
}

/**
 * Camera pipeline — 5 stages, only Stage 3 (compression) is replaceable.
 *
 * Stage 1: Acquire  — ImageProxy from CameraX (done by CameraAnalyzer)
 * Stage 2: Convert  — Extract planes from ImageProxy
 * Stage 3: Compress — [CameraCompressor] implementation (JPEG, RAW, H264 in v1.1)
 * Stage 4: Packetize — [PacketSerializer.cameraFrame] → payload bytes
 * Stage 5: Publish  — [TransportManager.send] → byte pipe
 *
 * To switch encoding: set [compressor] to a different [CameraCompressor] implementation.
 * The pipeline is otherwise unchanged.
 */
class CameraPipeline(
    private val transportManager: TransportManager,
    private val deviceId: Byte,
    var compressor: CameraCompressor = JpegCompressor()
) {
    private val seq = AtomicInteger(0)
    private var lastFrameNs = 0L

    /**
     * Process a single camera frame through the full 5-stage pipeline.
     * Called from the camera executor thread — must not block.
     */
    fun process(image: ImageProxy, metadata: FrameMetadata) {
        val pipelineStartNs = System.nanoTime()

        try {
            // Stage 2 + 3: Convert + Compress
            val compressed = compressor.compress(image)

            // Stage 4: Packetize (with camera metadata)
            val payload = PacketSerializer.cameraFrame(
                width            = image.width,
                height           = image.height,
                encoding         = compressor.encoding,
                quality          = if (compressor is JpegCompressor) (compressor as JpegCompressor).quality else 0,
                frameId          = metadata.frameId,
                exposureTimeNs   = 0L,  // Camera2 interop needed for true value; 0 = unavailable
                iso              = 0,   // Camera2 interop needed; 0 = unavailable
                sensorTimestampNs = metadata.sensorTimestampNs,
                data             = compressed
            )

            // Stage 5: Publish
            val packet = AsrPacket.encode(
                topicId  = TopicId.CAMERA_FRAME.value,
                seq      = seq.getAndIncrement(),
                payload  = payload,
                deviceId = deviceId
            )
            transportManager.send(packet)

            if (DevModeConfig.frameDebug) {
                val pipelineMs = (System.nanoTime() - pipelineStartNs) / 1_000_000f
                val latencyMs  = (pipelineStartNs - metadata.sensorTimestampNs) / 1_000_000f
                Log.d(TAG, "[FRAME] seq=${seq.get()-1} size=${compressed.size} " +
                        "pipeline=${pipelineMs}ms latency=${latencyMs}ms enc=${compressor.encoding.name}")
            }

            lastFrameNs = pipelineStartNs

        } catch (e: Exception) {
            Log.e(TAG, "Camera pipeline error on frame ${metadata.frameId}", e)
        }
    }

    companion object {
        private const val TAG = "AsrCamera"
    }
}
