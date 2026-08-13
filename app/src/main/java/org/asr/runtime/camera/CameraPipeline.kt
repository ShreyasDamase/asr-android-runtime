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
class JpegCompressor(val quality: Int = 75) : CameraCompressor {
    override val encoding = CameraEncoding.JPEG

    override fun compress(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0

        if (yRowStride == width && yPixelStride == 1) {
            val bytesToCopy = ySize.coerceAtMost(yBuffer.remaining())
            yBuffer.get(nv21, 0, bytesToCopy)
            pos = bytesToCopy
        } else {
            val rowBytes = ByteArray(yRowStride)
            for (row in 0 until height) {
                if (yBuffer.remaining() < yRowStride) break
                yBuffer.get(rowBytes, 0, yRowStride)
                for (col in 0 until width) {
                    val idx = col * yPixelStride
                    if (idx < rowBytes.size && pos < nv21.size) {
                        nv21[pos++] = rowBytes[idx]
                    }
                }
            }
        }

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vRow = ByteArray(vRowStride)
        val uRow = ByteArray(uRowStride)

        for (row in 0 until height / 2) {
            val vLen = vRowStride.coerceAtMost(vBuffer.remaining())
            val uLen = uRowStride.coerceAtMost(uBuffer.remaining())
            if (vLen > 0) vBuffer.get(vRow, 0, vLen)
            if (uLen > 0) uBuffer.get(uRow, 0, uLen)
            for (col in 0 until width / 2) {
                val vIdx = col * vPixelStride
                val uIdx = col * uPixelStride
                if (vIdx < vLen && uIdx < uLen && pos < nv21.size - 1) {
                    nv21[pos++] = vRow[vIdx]
                    nv21[pos++] = uRow[uIdx]
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
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
                topicId     = TopicId.CAMERA_FRAME.value,
                seq         = seq.getAndIncrement(),
                payload     = payload,
                deviceId    = deviceId,
                timestampNs = metadata.sensorTimestampNs
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
