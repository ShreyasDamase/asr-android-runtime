# ASR Runtime (Cameraros)

An Android-based robotics sensor runtime that orchestrates camera, IMU, audio, and environmental sensors into a centralized telemetry stream. Designed to be consumed by ROS 2 environments via a demand-driven architecture.

## Overview

The ASR Runtime acts as a "Hardware abstraction layer" on Android. It exposes low-level Android hardware as manageable modules that can be toggled, configured, and queried remotely via Android Intents (acting as a lightweight bridge for ROS 2 nodes).

---

## Getting Started

### Prerequisites
- Android Device (API 26+)
- `adb` installed on your development machine.

### Installation
1. Build and install the APK.
2. Open the app once to grant permissions via the **Dashboard UI**.
3. Use the **"App Settings"** button if permissions need manual override.

---

## Interface & Control (The "Bridge")

ASR uses **Android Intents** as its control interface. For ROS 2 developers, these map directly to node lifecycle and parameter concepts.

### 1. Lifecycle Control (Service-like)
Controlled via `am start-foreground-service`.

| ROS Equivalent | CLI Command (adb shell) | Description |
|:---|:---|:---|
| `ros2 run` | `am start-foreground-service -a org.asr.runtime.START org.asr.runtime/.core.AsrService` | Bootstraps core modules (`battery`, `diagnostics`). |
| `sigterm` | `am start-foreground-service -a org.asr.runtime.STOP org.asr.runtime/.core.AsrService` | Shuts down all hardware and the service. |
| `lifecycle:activate` | `am start-foreground-service -a org.asr.runtime.MODULE_START --es "module_id" "<ID>" org.asr.runtime/.core.AsrService` | Activates a specific hardware module. |
| `lifecycle:deactivate`| `am start-foreground-service -a org.asr.runtime.MODULE_STOP --es "module_id" "<ID>" org.asr.runtime/.core.AsrService` | Deactivates a specific hardware module. |

### 2. Parameters & Configuration
Change hardware settings on-the-fly.

| Parameter | Command | Data Types |
|:---|:---|:---|
| **Camera Config** | `-a org.asr.runtime.CAMERA_CONFIG --es "lens" "front" --ei "width" 1280 --ei "height" 720` | `lens`: String, `width/height`: Int |
| **TTS (Speaker)** | `-a org.asr.runtime.SPEAK --es "text" "Obstacle detected"` | `text`: String |

### 3. Profiles (Group Activation)
Quickly enable hardware sets for common robotics tasks.
- `profile mapping`: `camera`, `imu`
- `profile teleop`: `camera`, `audio`, `speaker`
- `profile navigation`: `imu`, `battery`

Example:
`adb shell am start-foreground-service -a org.asr.runtime.PROFILE_ENABLE --es "profile" "mapping" org.asr.runtime/.core.AsrService`

---

## Telemetry & Data (Topics)

### Status Query (Broadcast)
To get the current state of all topics and modules:
`adb shell am broadcast -a org.asr.runtime.STATUS`

**Output (Logcat `PhoneCamera`):**
```text
[MODULE STATES]
camera      : RUNNING
imu         : STOPPED
audio       : STOPPED
battery     : RUNNING
diagnostics : RUNNING
```

### Data Pipeline
- **Camera**: High-frequency YUV_420_888 frames via CameraX.
- **IMU**: Linear Acceleration, Gyroscope, Rotation Vector (at `SENSOR_DELAY_FASTEST`).
- **Audio**: 16-bit PCM, 16kHz Mono.
- **Diagnostics**: Heartbeat with FPS, Latency (ms), and Memory (MB) tracking.

---

## System Architecture

### Demand-Driven Design
ASR follows a **lazy-loading** philosophy. To save CPU and Power, only core modules start by default. Robotics nodes should "Enable" specific sensors only when they are actively needed for a mission.

### Module State Machine
1. `STOPPED`: Module initialized but idle.
2. `STARTING`: Resources (Camera/Microphone) being acquired.
3. `RUNNING`: Data flowing to pipeline.
4. `ERROR`: Hardware failure or permission missing.
5. `STOPPING`: Resource release in progress.

---

## Permissions
The runtime requires the following:
- `CAMERA` (Image Stream)
- `RECORD_AUDIO` (Mic Stream)
- `HIGH_SAMPLING_RATE_SENSORS` (High-freq IMU)
- `POST_NOTIFICATIONS` (Foreground Service)
- `ACTIVITY_RECOGNITION` (Step/Motion tracking)
