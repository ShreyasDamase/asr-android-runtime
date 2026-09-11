package org.asr.runtime.ui

import androidx.annotation.ColorRes
import org.asr.runtime.R

/**
 * UI-only representation of the entire ASR system state.
 * These models are optimized for easy binding in a Fragment or Adapter,
 * keeping business logic (like TopicId or ModuleState) out of the view layer.
 */

enum class RuntimeState(val label: String, @ColorRes val color: Int) {
    STOPPED("STOPPED", R.color.asr_status_stopped),
    STARTING("STARTING", R.color.asr_status_connecting),
    RUNNING("RUNNING", R.color.asr_status_running),
    STOPPING("STOPPING", R.color.asr_status_stopped),
    ERROR("ERROR", R.color.asr_status_error)
}

data class RuntimeUiState(
    val runtimeState: RuntimeState = RuntimeState.STOPPED,
    val uptime: String = "00:00:00",
    val deviceId: String = "Unknown"
)

enum class TcpState(val label: String, @ColorRes val color: Int) {
    DISCONNECTED("DISCONNECTED", R.color.asr_status_stopped),
    CONNECTING("CONNECTING", R.color.asr_status_connecting),
    CONNECTED("CONNECTED", R.color.asr_status_running),
    ERROR("ERROR", R.color.asr_status_error)
}

data class TcpUiState(
    val state: TcpState = TcpState.DISCONNECTED,
    val host: String = "127.0.0.1",
    val port: String = "4242",
    val adbMode: Boolean = true,
    val connectedClient: String = "Not connected"
)

enum class Ros2DiscoveryState(val label: String, @ColorRes val color: Int) {
    DISCONNECTED("OFF", R.color.asr_status_stopped),
    INITIALIZING("INIT", R.color.asr_status_connecting),
    DISCOVERING("DISCOVERING", R.color.asr_ros_discovering),
    ACTIVE("ACTIVE", R.color.asr_ros_publishing),
    ERROR("ERROR", R.color.asr_status_error)
}

data class Ros2UiState(
    val enabled: Boolean = false,
    val discovery: Ros2DiscoveryState = Ros2DiscoveryState.DISCONNECTED,
    val domainId: String = "0",
    val nodeName: String = "Unknown",
    val nodeNamespace: String = "Unknown"
)

enum class SensorRuntimeState(val label: String, @ColorRes val color: Int) {
    STOPPED("STOPPED", R.color.asr_status_stopped),
    RUNNING("RUNNING", R.color.asr_status_running),
    ERROR("ERROR", R.color.asr_status_error),
    NOT_AVAILABLE("N/A", R.color.asr_status_not_available)
}

enum class SensorRosState(val label: String, @ColorRes val color: Int) {
    IDLE("IDLE", R.color.asr_ros_not_publishing),
    PUBLISHING("PUBLISHING", R.color.asr_ros_publishing),
    NOT_AVAILABLE("N/A", R.color.asr_status_not_available)
}

enum class ControlSource(val label: String, @ColorRes val color: Int) {
    LOCAL("LOCAL", R.color.asr_control_local),
    REMOTE("REMOTE", R.color.asr_control_remote),
    AUTO("AUTO", R.color.asr_control_auto)
}

data class SensorUiModel(
    val id: String,
    val name: String,
    val iconRes: Int,
    val hardware: Boolean = true, // Present?
    val runtime: SensorRuntimeState = SensorRuntimeState.STOPPED,
    val ros: SensorRosState = SensorRosState.IDLE,
    val control: ControlSource = ControlSource.LOCAL,
    val rate: String? = null,
    val topic: String? = null,
    val expanded: Boolean = false
)
