package org.asr.runtime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.asr.runtime.R
import org.asr.runtime.core.AsrService
import org.asr.runtime.core.DiagnosticsSnapshot
import org.asr.runtime.core.RuntimeState as ServiceRuntimeState
import org.asr.runtime.ros2.Ros2State as ServiceRos2State

class MainViewModel : ViewModel() {

    private val _runtimeUiState = MutableStateFlow(RuntimeUiState())
    val runtimeUiState: StateFlow<RuntimeUiState> = _runtimeUiState.asStateFlow()

    private val _tcpUiState = MutableStateFlow(TcpUiState())
    val tcpUiState: StateFlow<TcpUiState> = _tcpUiState.asStateFlow()

    private val _ros2UiState = MutableStateFlow(Ros2UiState())
    val ros2UiState: StateFlow<Ros2UiState> = _ros2UiState.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    private val _sensors = MutableStateFlow<List<SensorUiModel>>(emptyList())
    val sensors: StateFlow<List<SensorUiModel>> = _sensors.asStateFlow()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private var asrService: AsrService? = null
    private val serviceJobs = mutableListOf<Job>()

    fun setService(service: AsrService?) {
        this.asrService = service
        _isServiceBound.value = (service != null)

        serviceJobs.forEach { it.cancel() }
        serviceJobs.clear()

        if (service != null) {
            serviceJobs += viewModelScope.launch {
                service.runtimeState.collect { state ->
                    _runtimeUiState.update { it.copy(runtimeState = mapRuntimeState(state)) }
                }
            }
            serviceJobs += viewModelScope.launch {
                service.clientInfo.collect { info ->
                    _tcpUiState.update { it.copy(
                        state = if (info != null) TcpState.CONNECTED else TcpState.DISCONNECTED,
                        connectedClient = info?.application ?: "Not connected"
                    ) }
                }
            }
            serviceJobs += viewModelScope.launch {
                service.diagnostics.collect { diag ->
                    _diagnostics.value = diag
                    _runtimeUiState.update { it.copy(uptime = formatUptime(diag.uptimeMs)) }
                }
            }
            serviceJobs += viewModelScope.launch {
                service.ros2Status.collect { status ->
                    _ros2UiState.update { it.copy(
                        enabled = service.ros2Runtime.isEnabled(),
                        discovery = mapRos2State(status.state),
                        domainId = status.domainId.toString(),
                        nodeName = status.nodeName ?: "Unknown",
                        nodeNamespace = status.nodeNamespace ?: "Unknown"
                    ) }
                }
            }
            serviceJobs += viewModelScope.launch {
                while (true) {
                    val moduleStatus = service.getModuleStatus()
                    updateSensors(moduleStatus)
                    delay(1000)
                }
            }
        } else {
            resetUiState()
        }
    }

    private fun mapRuntimeState(state: ServiceRuntimeState): RuntimeState = when (state) {
        ServiceRuntimeState.STOPPED -> RuntimeState.STOPPED
        ServiceRuntimeState.STARTING -> RuntimeState.STARTING
        ServiceRuntimeState.CONNECTING -> RuntimeState.STARTING
        ServiceRuntimeState.CONNECTED -> RuntimeState.RUNNING
        ServiceRuntimeState.STREAMING -> RuntimeState.RUNNING
        ServiceRuntimeState.STOPPING -> RuntimeState.STOPPING
        ServiceRuntimeState.ERROR -> RuntimeState.ERROR
    }

    private fun mapRos2State(state: ServiceRos2State): Ros2DiscoveryState = when (state) {
        ServiceRos2State.DISABLED -> Ros2DiscoveryState.DISCONNECTED
        ServiceRos2State.INITIALIZING -> Ros2DiscoveryState.INITIALIZING
        ServiceRos2State.ACTIVE -> Ros2DiscoveryState.ACTIVE
        ServiceRos2State.DISCONNECTED -> Ros2DiscoveryState.DISCONNECTED
        ServiceRos2State.ERROR -> Ros2DiscoveryState.ERROR
    }

    private fun updateSensors(moduleStatus: Map<String, String>) {
        val currentList = _sensors.value
        val newList = listOf(
            createSensorModel("camera", "Camera", android.R.drawable.ic_menu_camera, moduleStatus, currentList),
            createSensorModel("imu", "IMU", android.R.drawable.ic_menu_compass, moduleStatus, currentList),
            createSensorModel("gps", "GPS", android.R.drawable.ic_menu_mylocation, moduleStatus, currentList),
            createSensorModel("battery", "Battery", android.R.drawable.ic_lock_idle_low_battery, moduleStatus, currentList),
            createSensorModel("environment", "Environment", android.R.drawable.ic_menu_day, moduleStatus, currentList),
            createSensorModel("audio", "Microphone", android.R.drawable.ic_btn_speak_now, moduleStatus, currentList),
            createSensorModel("speaker", "Speaker", android.R.drawable.ic_lock_silent_mode_off, moduleStatus, currentList)
        )
        _sensors.value = newList
    }

    private fun createSensorModel(id: String, name: String, icon: Int, statusMap: Map<String, String>, currentList: List<SensorUiModel>): SensorUiModel {
        val status = statusMap[id] ?: "STOPPED"
        val expanded = currentList.find { it.id == id }?.expanded ?: false
        return SensorUiModel(
            id = id,
            name = name,
            iconRes = icon,
            runtime = when (status) {
                "RUNNING", "STREAMING" -> SensorRuntimeState.RUNNING
                "ERROR" -> SensorRuntimeState.ERROR
                "NOT_AVAILABLE" -> SensorRuntimeState.NOT_AVAILABLE
                else -> SensorRuntimeState.STOPPED
            },
            expanded = expanded
        )
    }

    private fun resetUiState() {
        _runtimeUiState.value = RuntimeUiState()
        _tcpUiState.value = TcpUiState()
        _ros2UiState.value = Ros2UiState()
        _diagnostics.value = DiagnosticsSnapshot()
        _sensors.value = emptyList()
    }

    private fun formatUptime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60))
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    // ---- Actions ----

    fun onStartRuntime() = asrService?.startRuntime()
    fun onStopRuntime() = asrService?.stopRuntime()
    fun onRestartRuntime() = asrService?.restartRuntime()
    fun onResetRuntime() = asrService?.resetRuntime()

    fun onTcpConnect(host: String, port: Int) = asrService?.reconnectTransport(host, port)
    fun onTcpDisconnect() = asrService?.stopTransport()
    
    fun onRos2EnabledChanged(enabled: Boolean) = asrService?.setRos2Enabled(enabled)

    fun onSensorToggle(sensor: SensorUiModel) {
        _sensors.update { list ->
            list.map { if (it.id == sensor.id) it.copy(expanded = !it.expanded) else it }
        }
    }

    fun onSensorStart(sensor: SensorUiModel) {
        asrService?.startModule(sensor.id)
    }

    fun onSensorStop(sensor: SensorUiModel) {
        asrService?.stopModule(sensor.id)
    }
}
