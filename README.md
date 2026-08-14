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

## 2. Sensor Module Control Reference

Individual hardware modules can be started or stopped dynamically to optimize thermal performance and battery life.

| Module ID | Hardware | Output Stream / Topic |
|:---|:---|:---|
| `camera` | CameraX Video Stream | 640x480 / 1280x720 JPEG frames (Topic `0x01`) |
| `imu` | Accel, Gyro, Mag, Rotation | 400 Hz Fused IMU + Raw Mag (Topic `0x02`) |
| `gps` | GPS / GNSS Location | Lat, Lon, Alt, Speed, Bearing (Topic `0x03`) |
| `audio` | Microphone Array | 16 kHz Mono PCM + RMS/VAD Telemetry (Topics `0x05`, `0x10`) |
| `speaker` | Text-To-Speech (TTS) | Robot Voice Synthesis & Alerts (Command Topic `0x08`) |
| `battery` | Battery & Thermals | Voltage, Level, Temp °C, Charging status (Topic `0x07`) |
| `environment` | Ambient Light Sensor | Lux illuminance levels (Topic `0x0F`) |

### Starting a Specific Module
```bash
# Start Audio Microphone Module
adb shell "am start-foreground-service -a org.asr.runtime.MODULE_START --es module_id audio org.asr.runtime/org.asr.runtime.core.AsrService"

# Start Camera Module
adb shell "am start-foreground-service -a org.asr.runtime.MODULE_START --es module_id camera org.asr.runtime/org.asr.runtime.core.AsrService"

# Start IMU Module
adb shell "am start-foreground-service -a org.asr.runtime.MODULE_START --es module_id imu org.asr.runtime/org.asr.runtime.core.AsrService"
```

### Stopping a Specific Module
```bash
# Stop Audio Microphone Module
adb shell "am start-foreground-service -a org.asr.runtime.MODULE_STOP --es module_id audio org.asr.runtime/org.asr.runtime.core.AsrService"

# Stop Camera Module
adb shell "am start-foreground-service -a org.asr.runtime.MODULE_STOP --es module_id camera org.asr.runtime/org.asr.runtime.core.AsrService"
```

---

## 3. Mission Profiles (Group Activation)

Activate preset sensor groups tailored for common autonomous robotics workloads:

```bash
# Full Sensor Suite (Camera + IMU + GPS + Audio + Speaker + Battery + Environment)
adb shell "am start-foreground-service -a org.asr.runtime.PROFILE_ENABLE --es profile full_sensor org.asr.runtime/org.asr.runtime.core.AsrService"

# Teleoperation Profile (Camera + Audio + Speaker)
adb shell "am start-foreground-service -a org.asr.runtime.PROFILE_ENABLE --es profile teleop org.asr.runtime/org.asr.runtime.core.AsrService"

# V-SLAM / Mapping Profile (Camera + IMU)
adb shell "am start-foreground-service -a org.asr.runtime.PROFILE_ENABLE --es profile mapping org.asr.runtime/org.asr.runtime.core.AsrService"

# Outdoor Navigation Profile (IMU + GPS + Battery)
adb shell "am start-foreground-service -a org.asr.runtime.PROFILE_ENABLE --es profile navigation org.asr.runtime/org.asr.runtime.core.AsrService"
```

---

## 4. Hardware Parameters & Controls

### 4.1 Camera Configuration
```bash
# Switch to Back Camera at 640x480 @ 30 FPS
adb shell "am start-foreground-service -a org.asr.runtime.CAMERA_CONFIG --es lens back --ei width 640 --ei height 480 --ei fps 30 org.asr.runtime/org.asr.runtime.core.AsrService"

# Switch to Front Camera at 1280x720
adb shell "am start-foreground-service -a org.asr.runtime.CAMERA_CONFIG --es lens front --ei width 1280 --ei height 720 org.asr.runtime/org.asr.runtime.core.AsrService"
```

### 4.2 Flashlight / Torch Control
```bash
# Turn Flashlight Torch ON
adb shell "am start-foreground-service -a org.asr.runtime.FLASHLIGHT --ez on true --ef brightness 1.0 org.asr.runtime/org.asr.runtime.core.AsrService"

# Turn Flashlight Torch OFF
adb shell "am start-foreground-service -a org.asr.runtime.FLASHLIGHT --ez on false org.asr.runtime/org.asr.runtime.core.AsrService"
```

### 4.3 Text-To-Speech (TTS) Voice Synthesis
```bash
# Speak custom voice command
adb shell "am start-foreground-service -a org.asr.runtime.SPEAK --es text 'ASR robotics runtime active' org.asr.runtime/org.asr.runtime.core.AsrService"

# Trigger obstacle alert
adb shell "am start-foreground-service -a org.asr.runtime.SPEAK --es text 'Warning. Dynamic obstacle detected ahead.' org.asr.runtime/org.asr.runtime.core.AsrService"
```

---

## 5. Live Diagnostics & Logcat Telemetry

### Query Live Module Status via Broadcast
```bash
adb shell "am broadcast -a org.asr.runtime.STATUS"
```

### Real-Time Logcat Telemetry Filter
```bash
# Stream runtime diagnostics, frame rates, and socket telemetry
adb logcat -s PhoneCamera:V AsrAudio:V AsrService:V AsrTransport:V
```
