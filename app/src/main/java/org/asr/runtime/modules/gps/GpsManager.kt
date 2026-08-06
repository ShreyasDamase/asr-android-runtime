package org.asr.runtime.modules.gps

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.PacketSerializer
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.transport.TransportManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPS hardware driver using Android LocationManager (AOSP-compatible).
 *
 * No Google Play Services dependency — works on custom ROMs, LineageOS,
 * and AOSP robotics devices.
 *
 * Provider fallback priority:
 *   GPS_PROVIDER → NETWORK_PROVIDER → PASSIVE_PROVIDER
 *
 * ACCESS_FINE_LOCATION permission must be granted before start() is called.
 * start() will transition to ERROR state if permission is missing.
 */
class GpsManager(
    private val context: Context,
    private val transportManager: TransportManager,
    private val deviceId: Byte
) : SensorModule, LocationListener {

    override val id: String = "gps"
    override var state: ModuleState = ModuleState.STOPPED

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val seq = AtomicInteger(0)

    @Volatile private var activeProvider: String? = null

    override fun start() {
        state = ModuleState.STARTING

        // Determine best available provider
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else ->
                LocationManager.PASSIVE_PROVIDER
        }

        try {
            @Suppress("MissingPermission")
            locationManager.requestLocationUpdates(
                provider,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                this
            )
            activeProvider = provider
            state = ModuleState.RUNNING
            Log.i(TAG, "GPS started with provider='$provider'")
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS start failed — ACCESS_FINE_LOCATION permission not granted", e)
            state = ModuleState.ERROR
        } catch (e: Exception) {
            Log.e(TAG, "GPS start failed", e)
            state = ModuleState.ERROR
        }
    }

    override fun onLocationChanged(location: Location) {
        val payload = PacketSerializer.gps(
            lat      = location.latitude,
            lon      = location.longitude,
            alt      = location.altitude.toFloat(),
            accuracy = location.accuracy,
            bearing  = location.bearing,
            speed    = location.speed
        )
        val packet = AsrPacket.encode(
            topicId  = TopicId.GPS.value,
            seq      = seq.getAndIncrement(),
            payload  = payload,
            deviceId = deviceId
        )
        transportManager.send(packet)

        Log.d(TAG, "[GPS] lat=${location.latitude} lon=${location.longitude} acc=${location.accuracy}m seq=${seq.get() - 1}")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "[GPS] Provider enabled: $provider")
    }
    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "[GPS] Provider disabled: $provider")
        if (provider == activeProvider) state = ModuleState.ERROR
    }

    override fun stop() {
        state = ModuleState.STOPPING
        locationManager.removeUpdates(this)
        activeProvider = null
        Log.i(TAG, "GPS stopped")
        state = ModuleState.STOPPED
    }

    override fun release() = stop()

    override fun capabilities() = listOf("gps", "latitude", "longitude", "altitude", "accuracy", "bearing", "speed")

    override fun status(): Map<String, Any> = mapOf(
        "id"       to id,
        "state"    to state.name,
        "provider" to (activeProvider ?: "none"),
        "seq"      to seq.get()
    )

    companion object {
        private const val TAG = "AsrGps"
        private const val MIN_TIME_MS   = 100L   // 10 Hz max
        private const val MIN_DISTANCE_M = 0f    // update on any movement
    }
}
