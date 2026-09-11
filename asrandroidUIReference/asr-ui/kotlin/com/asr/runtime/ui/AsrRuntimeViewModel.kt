package com.asr.runtime.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for the ASR runtime screen. Fragment observes these
 * flows and never mutates view state directly — every button click calls a
 * method here, which updates state, which the Fragment's BindingAdapters render.
 *
 * TODO: replace the in-memory mutation below with real calls into your
 * RuntimeManager / TcpTransportManager / Ros2TransportManager / SensorManager.
 * Nothing here assumes Compose; this is plain StateFlow + LiveData-friendly.
 */
class AsrRuntimeViewModel : ViewModel() {

    private val _runtime = MutableStateFlow(RuntimeUiState())
    val runtime: StateFlow<RuntimeUiState> = _runtime

    private val _tcp = MutableStateFlow(TcpUiState())
    val tcp: StateFlow<TcpUiState> = _tcp

    private val _ros2 = MutableStateFlow(Ros2UiState())
    val ros2: StateFlow<Ros2UiState> = _ros2

    private val _sensors = MutableStateFlow<List<SensorUiModel>>(emptyList())
    val sensors: StateFlow<List<SensorUiModel>> = _sensors

    // ---- Runtime lifecycle ----

    fun onStartRuntime() {
        // TODO: RuntimeManager.start()
        _runtime.update { it.copy(runtimeState = RuntimeState.CONNECTING) }
    }

    fun onStopRuntime() {
        // TODO: RuntimeManager.stop()
        _runtime.update { it.copy(runtimeState = RuntimeState.STOPPED) }
    }

    fun onRestartRuntime() {
        // TODO: RuntimeManager.restart()
        _runtime.update { it.copy(runtimeState = RuntimeState.CONNECTING) }
    }

    // ---- TCP transport (independent of ROS2) ----

    fun onTcpConnect(host: String, port: String, adbMode: Boolean) {
        // TODO: TcpTransportManager.connect(host, port, adbMode)
        _tcp.update { it.copy(state = TcpState.CONNECTING, host = host, port = port, adbMode = adbMode) }
    }

    fun onTcpDisconnect() {
        // TODO: TcpTransportManager.disconnect()
        _tcp.update { it.copy(state = TcpState.DISCONNECTED, connectedClient = "Not connected") }
    }

    fun onTcpReconnect() {
        // TODO: TcpTransportManager.reconnect()
        _tcp.update { it.copy(state = TcpState.CONNECTING) }
    }

    // ---- ROS2 transport (independent of TCP) ----

    fun onRos2EnabledChanged(enabled: Boolean) {
        // TODO: Ros2TransportManager.setEnabled(enabled)
        _ros2.update {
            it.copy(
                enabled = enabled,
                discovery = if (enabled) Ros2DiscoveryState.DISCOVERING else Ros2DiscoveryState.DISCONNECTED
            )
        }
    }

    // ---- Sensors ----

    fun onSensorRowToggled(sensor: SensorUiModel) {
        _sensors.update { list ->
            list.map { if (it.id == sensor.id) it.copy(expanded = !it.expanded) else it }
        }
    }

    fun onSensorStart(sensor: SensorUiModel) {
        if (sensor.control == ControlSource.REMOTE) return // UI must never override REMOTE locally
        // TODO: SensorManager.start(sensor.id)
        _sensors.update { list ->
            list.map { if (it.id == sensor.id) it.copy(runtime = SensorRuntimeState.RUNNING) else it }
        }
    }

    fun onSensorStop(sensor: SensorUiModel) {
        if (sensor.control == ControlSource.REMOTE) return
        // TODO: SensorManager.stop(sensor.id)
        _sensors.update { list ->
            list.map { if (it.id == sensor.id) it.copy(runtime = SensorRuntimeState.STOPPED) else it }
        }
    }

    fun onResetRuntime() {
        // TODO: RuntimeManager.factoryResetRuntime() — confirm via dialog before calling this
    }
}
