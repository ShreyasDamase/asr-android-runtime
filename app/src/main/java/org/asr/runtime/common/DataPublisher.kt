package org.asr.runtime.common

/**
 * Bridge between hardware managers and transport layers.
 * Decouples the source of data from the method of transmission.
 */
interface DataPublisher {
    /**
     * Publishes data to a specific topic.
     */
    fun publish(topic: String, data: ByteArray)
    
    /**
     * Publishes a structured message (will later handle binary serialization).
     */
    fun <T> publishMessage(topic: String, message: T)
}
