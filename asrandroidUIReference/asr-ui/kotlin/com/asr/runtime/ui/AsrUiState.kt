package com.asr.runtime.ui

import com.asr.runtime.R

/** Top-level runtime lifecycle. Owns nothing about transports or sensors. */
enum class RuntimeState { STOPPED, CONNECTING, RUNNING, ERROR }

/** TCP is its own state machine — never conflated with ROS2. */
enum class TcpState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** ROS2 has a distinct discovery lifecycle; "enabled" is a separate boolean. */
enum class Ros2DiscoveryState { DISCOVERING, CONNECTED, DISCONNECTED, ERROR }

/** Hardware presence is independent of whether it's currently running. */
enum class HardwareAvailability { AVAILABLE, NOT_AVAILABLE }

/** Runtime state of a single sensor capability. BLOCKED = hardware available but
 *  externally disabled (e.g. system Location off), distinct from STOPPED (operator choice). */
enum class SensorRuntimeState { STOPPED, RUNNING, BLOCKED, ERROR }

enum class RosPublishState { PUBLISHING, NOT_PUBLISHING }

/** Who is currently allowed to drive this sensor's start/stop. */
enum class ControlSource { LOCAL, REMOTE, AUTO }

data class RuntimeUiState(
    val runtimeState: RuntimeState = RuntimeState.STOPPED,
    val uptime: String = "00:00:00",
    val deviceId: String = "-"
)

data class TcpUiState(
    val state: TcpState = TcpState.DISCONNECTED,
    val host: String = "",
    val port: String = "4242",
    val adbMode: Boolean = false,
    val connectedClient: String = "Not connected"
)

data class Ros2UiState(
    val enabled: Boolean = false,
    val discovery: Ros2DiscoveryState = Ros2DiscoveryState.DISCONNECTED,
    val domainId: Int = 0,
    val nodeName: String = "-",
    val namespace: String = "-"
)

data class SensorUiModel(
    val id: String,
    val displayName: String,
    val iconRes: Int,
    val hardware: HardwareAvailability,
    val runtime: SensorRuntimeState,
    val ros: RosPublishState,
    val control: ControlSource,
    val rateHz: Float? = null,
    val topic: String? = null,
    val expanded: Boolean = false
) {
    /** Worst-of rollup shown on the collapsed row's single dot. */
    fun combinedSeverity(): Pair<String, Int> = when {
        hardware == HardwareAvailability.NOT_AVAILABLE -> "N/A" to R.color.asr_status_not_available
        runtime == SensorRuntimeState.ERROR -> "ERROR" to R.color.asr_status_error
        runtime == SensorRuntimeState.BLOCKED -> "BLOCKED" to R.color.asr_status_blocked
        runtime == SensorRuntimeState.RUNNING && ros == RosPublishState.PUBLISHING -> "PUBLISHING" to R.color.asr_ros_publishing
        runtime == SensorRuntimeState.RUNNING -> "RUNNING" to R.color.asr_status_running
        else -> "STOPPED" to R.color.asr_status_stopped
    }
}
