package org.asr.runtime.transport

import android.util.Log
import org.asr.runtime.common.Transport

/**
 * Default transport that redirects published data to Logcat.
 */
class LogTransport : Transport {
    override val type: String = "logcat"
    override var isConnected: Boolean = false
        private set

    override fun connect() {
        isConnected = true
        Log.i("AsrLogTransport", "Log transport connected")
    }

    override fun publish(topic: String, data: ByteArray) {
        // Only log size in Logcat to avoid spamming binary data
        Log.d("PhoneCamera", "[PUBLISH] topic=$topic size=${data.size} bytes")
    }

    override fun disconnect() {
        isConnected = false
        Log.i("AsrLogTransport", "Log transport disconnected")
    }
}
