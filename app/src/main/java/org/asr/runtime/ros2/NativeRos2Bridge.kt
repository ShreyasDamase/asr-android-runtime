package org.asr.runtime.ros2

import android.util.Log

object NativeRos2Bridge {
    private const val TAG = "NativeRos2Bridge"
    private val LIB_AVAILABLE: Boolean = try {
        System.loadLibrary("asr_ros2")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "ROS 2 native bridge library not found; ROS 2 runtime remains disabled until the native rcl/rmw library is packaged.", e)
        false
    }

    fun isAvailable(): Boolean = LIB_AVAILABLE

    private var messageListener: MessageListener? = null

    interface MessageListener {
        fun onStringMessage(topic: String, message: String)
    }

    fun setMessageListener(listener: MessageListener?) {
        messageListener = listener
    }

    // Called from native code
    fun onStringMessageReceived(topic: String, message: String) {
        messageListener?.onStringMessage(topic, message)
    }

    external fun initialize(domainId: Int, nodeName: String): Boolean
    external fun startNode(domainId: Int, nodeName: String): Boolean
    external fun subscribeString(topicName: String): Boolean
    external fun publishString(topicName: String, message: String): Boolean
    external fun publishImu(topicName: String, accel: DoubleArray, gyro: DoubleArray, orientation: DoubleArray, timestampNs: Long): Boolean
    external fun publishNavSatFix(topicName: String, latitude: Double, longitude: Double, altitude: Double, accuracy: Float, timestampNs: Long): Boolean
    external fun publishBattery(topicName: String, percentage: Float, temperature: Float, voltageMv: Float, charging: Boolean): Boolean
    external fun publishScalar(topicName: String, value: Double, timestampNs: Long, kind: Int): Boolean
    external fun publishCompressedImage(topicName: String, data: ByteArray, format: String, timestampNs: Long): Boolean
    external fun publishCameraInfo(topicName: String, width: Int, height: Int, timestampNs: Long): Boolean
    external fun shutdown()
    external fun isNodeReady(): Boolean
}
