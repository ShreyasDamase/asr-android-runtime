# ASR Runtime (Cameraros)

A lightweight robotics sensor runtime for Android that orchestrates camera, IMU, audio, and environmental sensors into a centralized telemetry stream.

## CLI & External Control

The ASR Runtime is **demand-driven**. By default, starting the service only enables `battery` and `diagnostics` modules. Other sensors must be enabled explicitly via intents or profiles.

### Service Controls (`am start-foreground-service`)

| Command | Action | Description |
|:---|:---|:---|
| **Start** | `adb shell am start-foreground-service -a org.asr.runtime.START org.asr.runtime/.core.AsrService` | Bootstraps the service (Core modules only). |
| **Stop** | `adb shell am start-foreground-service -a org.asr.runtime.STOP org.asr.runtime/.core.AsrService` | Shuts down all modules and the service. |
| **Module Start** | `adb shell am start-foreground-service -a org.asr.runtime.MODULE_START --es "module_id" "camera" org.asr.runtime/.core.AsrService` | Starts a specific module. |
| **Module Stop** | `adb shell am start-foreground-service -a org.asr.runtime.MODULE_STOP --es "module_id" "camera" org.asr.runtime/.core.AsrService` | Stops a specific module. |
| **Profile Enable** | `adb shell am start-foreground-service -a org.asr.runtime.PROFILE_ENABLE --es "profile" "mapping" org.asr.runtime/.core.AsrService` | Enables a group of modules for a task. |

### Available Profiles
- **mapping**: camera, imu
- **teleop**: camera, audio, speaker
- **navigation**: imu, battery

### Module States
Each module tracks its lifecycle: `STOPPED`, `STARTING`, `RUNNING`, `ERROR`, `STOPPING`. Use the **Status** command to view them.

### Status Query (`am broadcast`)

Query the current health and telemetry of the system.

| Command | Action | Description |
|:---|:---|:---|
| **Status** | `adb shell am broadcast -a org.asr.runtime.STATUS` | Dumps current FPS, Latency, Battery, and Audio status to Logcat under the tag `PhoneCamera`. |

---

## Technical Overview

### Expose Hardware Modules
- **Camera**: High-frequency frame analysis via CameraX.
- **IMU**: Accelerometer, Gyroscope, Gravity, and Rotation Vector.
- **Environment**: Light, Proximity, Pressure, Temperature, and Humidity.
- **Audio**: Mono PCM 16-bit 16kHz microphone stream with RMS/dBFS analysis.
- **Battery**: Real-time voltage, level, and thermal monitoring.

### Performance
- **Low Latency**: Uses `SENSOR_DELAY_FASTEST` for high-frequency robotics data.
- **Buffer Safety**: Implemented using standard Android XML and Service components to minimize overhead compared to UI-heavy frameworks.
- **Opaque UI**: Includes a hardware/permission dashboard for easy configuration without background distractions.

## Permissions
The following permissions are required and can be managed via the App Settings deep link in the main UI:
- `CAMERA`
- `RECORD_AUDIO`
- `ACTIVITY_RECOGNITION`
- `POST_NOTIFICATIONS`
- `HIGH_SAMPLING_RATE_SENSORS` (Declared in Manifest for Android 12+)
