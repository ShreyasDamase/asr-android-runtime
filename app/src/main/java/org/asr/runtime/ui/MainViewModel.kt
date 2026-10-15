package org.asr.runtime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.asr.runtime.core.AsrService
import org.asr.runtime.core.ClientInfo
import org.asr.runtime.core.DiagnosticsSnapshot
import org.asr.runtime.core.RuntimeState
import org.asr.runtime.ros2.Ros2State
import org.asr.runtime.ros2.Ros2Status

class MainViewModel : ViewModel() {

    private val _runtimeState = MutableStateFlow(RuntimeState.STOPPED)
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    private val _clientInfo = MutableStateFlow<ClientInfo?>(null)
    val clientInfo: StateFlow<ClientInfo?> = _clientInfo.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    private val _moduleStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val moduleStatus: StateFlow<Map<String, String>> = _moduleStatus.asStateFlow()

    private val _ros2Status = MutableStateFlow(Ros2Status(Ros2State.DISABLED))
    val ros2Status: StateFlow<Ros2Status> = _ros2Status.asStateFlow()

    /** True once onServiceConnected fires; false when service is unbound/destroyed. */
    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private var asrService: AsrService? = null

    /** Collectors bound to the current service instance; cancelled on rebind/unbind. */
    private val serviceJobs = mutableListOf<Job>()

    fun setService(service: AsrService?) {
        this.asrService = service
        _isServiceBound.value = (service != null)

        // Tear down collectors from any previous service instance so they don't
        // pile up (onServiceConnected can fire again after a service crash +
        // BIND_AUTO_CREATE re-creation), each one leaking an infinite loop.
        serviceJobs.forEach { it.cancel() }
        serviceJobs.clear()

        if (service != null) {
            serviceJobs += viewModelScope.launch {
                service.runtimeState.collect { _runtimeState.value = it }
            }
            serviceJobs += viewModelScope.launch {
                service.clientInfo.collect { _clientInfo.value = it }
            }
            serviceJobs += viewModelScope.launch {
                service.diagnostics.collect { _diagnostics.value = it }
            }
            serviceJobs += viewModelScope.launch {
                service.ros2Status.collect { _ros2Status.value = it }
            }
            // Periodically pull module status from the service
            serviceJobs += viewModelScope.launch {
                while (true) {
                    _moduleStatus.value = service.getModuleStatus()
                    kotlinx.coroutines.delay(1000)
                }
            }
        } else {
            // Service disconnected — reset all live state to defaults
            _runtimeState.value = RuntimeState.STOPPED
            _clientInfo.value = null
            _diagnostics.value = DiagnosticsSnapshot()
            _moduleStatus.value = emptyMap()
            _ros2Status.value = Ros2Status(Ros2State.DISABLED)
        }
    }

    fun startRuntime() {
        asrService?.startRuntime()
    }

    fun stopRuntime() {
        asrService?.stopRuntime()
    }

    fun restartRuntime() {
        asrService?.restartRuntime()
    }

    fun resetRuntime() {
        asrService?.resetRuntime()
    }

    fun reconnectTransport() {
        asrService?.reconnectTransport()
    }

    /**
     * Reconnects the TCP transport to [host]:[port].
     * This is the primary entry point for both Wi-Fi and ADB mode:
     *   - Wi-Fi: pass the desktop's LAN IP, e.g. "192.168.1.42", port 4242
     *   - ADB:   pass "127.0.0.1", port 4242  (after adb forward tcp:4242 tcp:4242)
     */
    fun reconnectTransport(host: String, port: Int) {
        asrService?.reconnectTransport(host, port)
    }

    fun stopTransport() {
        asrService?.stopTransport()
    }

    fun shutdownForExit(onComplete: () -> Unit) {
        asrService?.shutdownForExit(onComplete) ?: onComplete()
    }

    fun setRos2Enabled(enabled: Boolean) {
        asrService?.setRos2Enabled(enabled)
    }

    fun setEnabledSensorModuleIds(moduleIds: Set<String>) {
        asrService?.setEnabledSensorModuleIds(moduleIds)
    }
}
