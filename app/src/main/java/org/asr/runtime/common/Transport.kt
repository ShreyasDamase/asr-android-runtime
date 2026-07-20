package org.asr.runtime.common

/**
 * Interface for data transmission between ASR and the ROS 2 host.
 */
interface Transport {
    fun connect()
    fun publish(topic: String, data: ByteArray)
    fun disconnect()
    val isConnected: Boolean
    val type: String
}
