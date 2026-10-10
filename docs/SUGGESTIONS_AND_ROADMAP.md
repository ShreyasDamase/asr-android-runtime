# ASR Roadmap & Architectural Suggestions

This document tracks high-value architectural ideas, upcoming robotics features, and performance considerations identified for future development cycles of **ASR Runtime** and **ASR Studio**.

---

## 1. Zero-Cost `tf_static` (Static Coordinate Frames)

### Overview
In standard ROS 2 robotics pipelines (e.g., Nav2, RTAB-Map, `robot_localization`), algorithms require spatial relationship models relating the robot body to its sensors.

### Proposed Implementation
- **Message Type**: `tf2_msgs/msg/TFMessage` (latched / `transient_local` QoS).
- **Frames**:
  - `phone_body` (or `base_link`) $\rightarrow$ `imu_link` (IMU orientation alignment).
  - `phone_body` $\rightarrow$ `camera_optical_frame` (Standard ROS optical frame: $Z$-forward, $X$-right, $Y$-down).
- **Why it fits ASR**:
  - Transmitted **only once** on participant discovery (latched).
  - **Zero continuous CPU/GPU overhead** and negligible battery usage.
  - Enables immediate visual integration with Foxglove Studio, RViz, and multi-sensor fusion nodes out of the box.

---

## 2. In-App Discovery & Network Configuration (UI Controls)

### Overview
Currently, `ROS_DOMAIN_ID` and DDS backend parameters are configured via build environment or defaults. Exposing runtime controls directly in the Android app simplifies field deployment.

### Proposed Implementation
- **Runtime `ROS_DOMAIN_ID` Picker**: Allow setting the target domain integer (0–232) directly from the UI before starting the runtime.
- **Unicast Peer IP List**: Input field for comma-separated IP addresses (e.g., `192.168.1.15, 10.0.0.5`) to bypass network environments that block standard UDP multicast (such as enterprise, campus, or restricted Wi-Fi routers).
- **Publish Rate Throttle (Hz)**: Optional throttle slider for IMU / telemetry to cap rates if running over bandwidth-constrained wireless links.

---

## 3. Dynamic Camera Quality & Resolution Controls

### Overview
Allow tuning camera resolution and JPEG compression quality on the fly to adapt to varying Wi-Fi signal strengths.

### Proposed Implementation
- **Preset Options**:
  - `480p @ 30 FPS` (Default, low latency, ~25 KB/frame).
  - `720p @ 15 FPS` (Higher spatial resolution for fiducials / object recognition).
  - `360p @ 30 FPS` (Ultra-low bandwidth mode for weak Wi-Fi).
- **JPEG Compression Quality Slider (40% – 90%)**:
  - Reducing JPEG quality from 85% to 65% cuts network payload size by ~40% with minimal impact on computer vision feature matching.

---

## 4. Hardware Health & Battery State Integration

### Overview
Expand battery and device health telemetry for autonomous robot docks.

### Proposed Implementation
- **`sensor_msgs/msg/BatteryState` Enhancements**:
  - Current charge percentage, voltage, battery temperature, and charging status (USB vs AC vs Unplugged).
- **Thermal State Throttle Warning**:
  - Expose device thermal status (Android `OnThermalStatusChangedListener`) on `/asr_android_<id>/status` to warn the host robot if the phone is operating in high-heat environments.

---

## 5. Host-Side Fiducial / AprilTag Pipeline (ROS 2 Companion)

### Overview
Provide a lightweight ROS 2 host launch configuration demonstrating practical vision applications.

### Proposed Implementation
- Host node subscribing to `/asr_android_<id>/camera/image/compressed`.
- Executes AprilTag / ArUco detection on the host computer (RTX/CPU) rather than straining the phone's mobile processor.
- Broadcasts detected tag coordinate frames on `/tf` relative to the phone's camera.

---

## Architectural Principles to Preserve

When implementing any future features, maintain the core design principles established in ASR:
1. **Headless & Low-Overhead**: Do not run heavy spatial compute (such as real-time 3D depth mesh generation or neural networks) on the phone. The phone serves as an efficient, low-latency sensor transmitter.
2. **Zero-Allocation Critical Loop**: Avoid allocating Java heap objects inside high-frequency camera and IMU callbacks to prevent Garbage Collection (GC) pauses.
3. **Clean Service Lifecycle**: Always ensure foreground services, cameras, and hardware sensors are fully unhooked and released when stopped or exited.
