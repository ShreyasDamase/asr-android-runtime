package org.asr.runtime.camera

/**
 * Lens values reported by the phone, relative to the sensor's active array.
 * These are device-reported values, not the result of a checkerboard calibration.
 *
 * @param distortion plumb_bob order: k1, k2, t1, t2, k3
 */
class CameraIntrinsics(
    private val fx: Double,
    private val fy: Double,
    private val cx: Double,
    private val cy: Double,
    val distortion: DoubleArray,
    private val activeWidth: Int,
    private val activeHeight: Int
) {
    /** Returns fx, fy, cx, cy for a stream scaled and center-cropped from the active array. */
    fun forStream(width: Int, height: Int): DoubleArray {
        val scale = maxOf(width.toDouble() / activeWidth, height.toDouble() / activeHeight)
        val cropX = (activeWidth - width / scale) / 2.0
        val cropY = (activeHeight - height / scale) / 2.0
        return doubleArrayOf(fx * scale, fy * scale, (cx - cropX) * scale, (cy - cropY) * scale)
    }

    companion object {
        /**
         * Parses a checkerboard result: WxH,fx,fy,cx,cy,k1,k2,t1,t2,k3.
         * Scales from the image size used for calibration, so any stream size can use it.
         */
        fun parseCalibration(text: String): CameraIntrinsics? {
            val parts = text.split(",").map { it.trim() }
            if (parts.size != 10) return null
            val size = parts[0].split("x")
            val width = size.getOrNull(0)?.toIntOrNull() ?: return null
            val height = size.getOrNull(1)?.toIntOrNull() ?: return null
            val numbers = parts.drop(1).map { it.toDoubleOrNull() ?: return null }
            if (width <= 0 || height <= 0 || numbers[0] <= 0.0 || numbers[1] <= 0.0) return null
            return CameraIntrinsics(
                fx = numbers[0],
                fy = numbers[1],
                cx = numbers[2],
                cy = numbers[3],
                distortion = numbers.subList(4, 9).toDoubleArray(),
                activeWidth = width,
                activeHeight = height
            )
        }

        /**
         * @param calibration LENS_INTRINSIC_CALIBRATION: fx, fy, cx, cy, s
         * @param androidDistortion LENS_DISTORTION: k1, k2, k3, p1, p2
         * @return null when the phone gives no usable focal length
         */
        fun fromCamera2(
            calibration: FloatArray?,
            androidDistortion: FloatArray?,
            activeWidth: Int,
            activeHeight: Int
        ): CameraIntrinsics? {
            if (calibration == null || calibration.size < 4 || calibration[0] <= 0f || calibration[1] <= 0f) return null
            if (activeWidth <= 0 || activeHeight <= 0) return null
            // Some phones report a zero principal point; use the image center in that case.
            val hasCenter = calibration[2] > 0f && calibration[3] > 0f
            val d = androidDistortion
            return CameraIntrinsics(
                fx = calibration[0].toDouble(),
                fy = calibration[1].toDouble(),
                cx = if (hasCenter) calibration[2].toDouble() else activeWidth / 2.0,
                cy = if (hasCenter) calibration[3].toDouble() else activeHeight / 2.0,
                distortion = if (d != null && d.size >= 5) {
                    doubleArrayOf(d[0].toDouble(), d[1].toDouble(), d[3].toDouble(), d[4].toDouble(), d[2].toDouble())
                } else {
                    DoubleArray(5)
                },
                activeWidth = activeWidth,
                activeHeight = activeHeight
            )
        }
    }
}
