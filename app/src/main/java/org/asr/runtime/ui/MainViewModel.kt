package org.asr.runtime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.asr.runtime.core.AsrService
import org.asr.runtime.core.ClientInfo
import org.asr.runtime.core.DiagnosticsSnapshot
import org.asr.runtime.core.RuntimeState

class MainViewModel : ViewModel() {

    private val _runtimeState = MutableStateFlow(RuntimeState.STOPPED)
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    private val _clientInfo = MutableStateFlow<ClientInfo?>(null)
    val clientInfo: StateFlow<ClientInfo?> = _clientInfo.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsSnapshot())
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics.asStateFlow()

    private val _moduleStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val moduleStatus: StateFlow<Map<String, String>> = _moduleStatus.asStateFlow()

    /** True once onServiceConnected fires; false when service is unbound/destroyed. */
    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private var asrService: AsrService? = null

    fun setService(service: AsrService?) {
        this.asrService = service
        _isServiceBound.value = (service != null)
        if (service != null) {
            viewModelScope.launch {
                service.runtimeState.collect { _runtimeState.value = it }
            }
            viewModelScope.launch {
                service.clientInfo.collect { _clientInfo.value = it }
            }
            viewModelScope.launch {
                service.diagnostics.collect { _diagnostics.value = it }
            }
            // Periodically pull module status from the service
            viewModelScope.launch {
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
}
