package org.asr.runtime.transport

import android.util.Log
import org.asr.runtime.common.DataPublisher
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import org.asr.runtime.common.Transport

/**
 * Manages active data transports for ASR.
 */
class TransportManager : SensorModule, DataPublisher {

    override val id: String = "transport"
    override var state: ModuleState = ModuleState.STOPPED

    private val transports = mutableListOf<Transport>()

    fun addTransport(transport: Transport) {
        transports.add(transport)
    }

    override fun start() {
        state = ModuleState.STARTING
        transports.forEach { it.connect() }
        Log.i("AsrTransport", "Transport manager started with ${transports.size} transports")
        state = ModuleState.RUNNING
    }

    override fun stop() {
        state = ModuleState.STOPPING
        transports.forEach { it.disconnect() }
        Log.i("AsrTransport", "Transport manager stopped")
        state = ModuleState.STOPPED
    }

    override fun release() {
        stop()
        transports.clear()
    }

    override fun publish(topic: String, data: ByteArray) {
        transports.forEach { 
            if (it.isConnected) {
                it.publish(topic, data)
            }
        }
    }

    override fun <T> publishMessage(topic: String, message: T) {
        // In v0.2, this will handle binary serialization (e.g., Protobuf or custom protocol)
    }
}
