# ASR Runtime (Cameraros) — Android Sensor Runtime for Robotics

An Android-based robotics sensor runtime that transforms commercial Android smartphones into high-performance, edge-calibrated multi-sensor suites for ROS 2 and robotics platforms.

---

## 1. Quick Start (Terminal & CLI First)

ASR is designed for headless operation. You can configure, run, and diagnose the entire runtime via standard `adb shell` commands from Linux (Ubuntu), macOS, or Windows terminals.

### 1.1 One-Click USB Port Forwarding
```bash
# Reverse TCP port 4242 to allow the phone to stream directly to your workstation localhost
adb reverse tcp:4242 tcp:4242
```

### 1.2 Start the Runtime Service
```bash
adb shell "am start-foreground-service -a org.asr.runtime.START org.asr.runtime/org.asr.runtime.core.AsrService"
```

### 1.3 Stop the Runtime Service
```bash
adb shell "am start-foreground-service -a org.asr.runtime.STOP org.asr.runtime/org.asr.runtime.core.AsrService"
```

### 1.4 Restart the Runtime (Full Re-initialization)
```bash
adb shell "am start-foreground-service -a org.asr.runtime.RESTART org.asr.runtime/org.asr.runtime.core.AsrService"
```

### 1.5 Emergency Reset & Relaunch App
```bash
adb shell "am force-stop org.asr.runtime && am start -n org.asr.runtime/.MainActivity"
```

---

## 2. ROS 2 Integration Guide

ASR includes a native ROS 2 transport powered by `Fast DDS`. It allows the Android device to appear as a first-class ROS 2 node on your Wi-Fi network.

### 2.1 Enable ROS 2 Runtime
ROS 2 is disabled by default. Enable it and set your Domain ID via ADB:
```bash
# Enable ROS 2 transport
adb shell "am broadcast -a org.asr.runtime.DEV_MODE --es flag ros2_enabled --ez value true"

# Set ROS Domain ID (default is 0)
adb shell "am broadcast -a org.asr.runtime.DEV_MODE --es flag ros2_domain_id --ei value 0"

# Restart service to apply changes
adb shell "am start-foreground-service -a org.asr.runtime.RESTART org.asr.runtime/org.asr.runtime.core.AsrService"
```

### 2.2 Permissions Setup
Ensure the app has necessary permissions for sensor data:
```bash
adb shell pm grant org.asr.runtime android.permission.CAMERA
adb shell pm grant org.asr.runtime android.permission.ACCESS_FINE_LOCATION
adb shell pm grant org.asr.runtime android.permission.RECORD_AUDIO
```

### 2.3 ROS 2 Topic Reference
Once active, the node `/asr_android` will publish/subscribe to the following:

| Topic Name | Message Type | Description |
|:---|:---|:---|
| `/asr_android/status` | `std_msgs/msg/String` | Periodic heartbeat and system status |
| `/asr_android/capabilities` | `std_msgs/msg/String` | Hardware capability JSON (published on startup) |
| `/asr_android/imu` | `sensor_msgs/msg/Imu` | High-rate Accel, Gyro, and Orientation |
| `/asr_android/gps` | `sensor_msgs/msg/NavSatFix` | GNSS Location data |
| `/asr_android/battery` | `sensor_msgs/msg/BatteryState` | Voltage, Level, Temp, and Charging status |
| `/asr_android/camera/image/compressed` | `sensor_msgs/msg/CompressedImage` | JPEG encoded camera stream |
| `/asr_android/camera/camera_info` | `sensor_msgs/msg/CameraInfo` | Camera intrinsics and resolution |
| `/asr_android/environment/illuminance` | `sensor_msgs/msg/Illuminance` | Ambient light intensity (Lux) |
| `/asr_android/test/in` | `std_msgs/msg/String` | Inbound test topic (Subscription) |

### 2.4 Example Workstation Commands
On your ROS 2 workstation (with `rmw_fastrtps_cpp` installed):

```bash
# Export RMW implementation
export RMW_IMPLEMENTATION=rmw_fastrtps_cpp

# Echo system capabilities
ros2 topic echo /asr_android/capabilities

# View live camera stream in RViz2
ros2 run rviz2 rviz2

# Send a test message TO the Android device
ros2 topic pub --once /asr_android/test/in std_msgs/msg/String "{data: 'Hello ASR'}"
```

---

## 3. Sensor Module Control Reference

Individual hardware modules can be started or stopped dynamically to optimize thermal performance and battery life.

| Module ID | Hardware |
|:---|:---|
| `camera` | CameraX Video Stream |
| `imu` | Accel, Gyro, Mag, Rotation |
| `gps` | GPS / GNSS Location |
| `audio` | Microphone Array |
| `speaker` | Text-To-Speech (TTS) |
| `battery` | Battery & Thermals |
| `environment` | Ambient Light & Pressure Sensors |

### Starting a Specific Module
```bash
adb shell "am start-foreground-service -a org.asr.runtime.MODULE_START --es module_id camera org.asr.runtime/org.asr.runtime.core.AsrService"
```

### Mission Profiles (Group Activation)
```bash
# Full Sensor Suite (Camera + IMU + GPS + Battery + Environment)
adb shell "am start-foreground-service -a org.asr.runtime.PROFILE_ENABLE --es profile full_sensor org.asr.runtime/org.asr.runtime.core.AsrService"
```

---

## 4. Hardware Parameters & Controls

### 4.1 Camera Orientation & Configuration
ASR automatically handles 90-degree sensor rotation to provide upright images in ROS 2.
```bash
# Switch to Back Camera at 640x480 @ 30 FPS
adb shell "am start-foreground-service -a org.asr.runtime.CAMERA_CONFIG --es lens back --ei width 640 --ei height 480 --ei fps 30 org.asr.runtime/org.asr.runtime.core.AsrService"
```

### 4.2 Flashlight / Torch Control
```bash
adb shell "am start-foreground-service -a org.asr.runtime.FLASHLIGHT --ez on true org.asr.runtime/org.asr.runtime.core.AsrService"
```

---

## 5. Live Diagnostics & Logcat Telemetry

### Query Live Module Status via Broadcast
```bash
adb shell "am broadcast -a org.asr.runtime.STATUS"
```

### Real-Time Logcat Telemetry Filter
```bash
# Stream runtime diagnostics, frame rates, and ROS 2 events
adb logcat -s PhoneCamera:V AsrAudio:V AsrService:V NativeRos2Bridge:V Ros2Runtime:V
```
