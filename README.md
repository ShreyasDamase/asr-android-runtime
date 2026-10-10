# ASR Android Runtime

ASR Runtime turns an Android phone into a ROS 2 sensor node. It publishes phone sensor data over DDS and can also connect to ASR Studio over TCP. Sensor runtime, TCP, and ROS 2 DDS have separate controls and lifecycles.

The selected Android DDS backend is **Cyclone DDS**. A Fast DDS build is retained for comparison. This repository contains the Android app and its ROS 2 host-side USB diagnostics package; it does not include ASR Studio.

## Current status

- Cyclone DDS was tested on Mac over USB tethering and Wi-Fi. Both paths discovered peers in under 10 seconds in the recorded tests; this is an observed result, not a network guarantee.
- Sensor delivery was observed on both paths. Ubuntu and Raspberry Pi live validation remain outstanding.
- The USB host monitor is optional for Wi-Fi. See [`asr_ros2/README.md`](asr_ros2/README.md).
- Source and native ROS 2 libraries are included, but a complete clean-machine native build has not yet been demonstrated. The build currently needs an Android ROS 2 prefix with headers and libraries, as described below.

## Requirements

- Android Studio or Android SDK with API 36, NDK, and CMake 3.22.1.
- An Android device with **arm64-v8a** support. The Cyclone variant requires Android API 28 or later; the Fast DDS variant has a lower minimum API level.
- The cross-built Android ROS 2 prefix used by the native bridge. Set `ROS2_ANDROID_PREFIX` to a prefix containing `include/` and `lib/`. The app's JNI `.so` files are tracked under `app/src/main/jniLibs/` and `app/src/cyclone/jniLibs/`.
- ROS 2 host environment for DDS integration; tested host setup used ROS 2 Lyrical and `rmw_fastrtps_cpp`.

The original local cross-build prefix is not part of this repository. Contributors need to provide a compatible Android ROS 2 prefix themselves; the helper scripts in `scripts/` are maintainer-oriented cross-build steps and require the ROS 2 source workspace and Android NDK.

## Build the app

From the repository root, set the prefix and build the Cyclone variant:

```bash
export ROS2_ANDROID_PREFIX=/path/to/android/ros2/install
./gradlew :app:assembleCycloneDebug
```

To build the Fast DDS comparison variant:

```bash
export ROS2_ANDROID_PREFIX=/path/to/android/ros2/install
./gradlew :app:assembleFastddsDebug
```

Cyclone and Fast DDS have separate app IDs and can be installed side by side. Do not run both sensor runtimes at the same time: they currently use the same ROS 2 node name on a given device.

## Run and connect

1. Install and open **ASR Runtime (Cyclone DDS)**, then grant the Android permissions for the sensors you want to use.
2. Start the sensor runtime in the app. This starts phone sensor capture; it does not automatically enable TCP or DDS.
3. Enable **Wi-Fi DDS**, **USB DDS**, or both in the ROS 2 section. For USB, also connect the cable and enable Android USB tethering.
4. Keep the phone and ROS 2 host on the same `ROS_DOMAIN_ID` (default `0`).
5. Inspect the phone from an existing robot node, or use standard ROS 2 tools on the host.

For a USB host-side diagnostic that stays running and reports discovered nodes and received sensor messages:

```bash
ros2 launch asr_ros2 connect.launch.py transport:=usb
```

This helper is USB-only. Wi-Fi DDS does not need it; use a normal ROS 2 node or subscriber over Wi-Fi. It is a diagnostic monitor, not a requirement for DDS discovery. Raspberry Pi use is expected with a compatible ROS 2 setup and USB-tether interface, but remains unverified.

TCP to ASR Studio is separate from DDS. For ADB TCP forwarding, the app’s TCP section shows the required host and port instructions; DDS does not use `adb reverse`.

## ROS 2 topics

The node namespace is device-specific: `/asr_android_<device-id>`. Topic names below are relative to that namespace, so two phones publish under different namespaces.

| Topic suffix | Type | Notes |
| --- | --- | --- |
| `status` | `std_msgs/msg/String` | Runtime heartbeat/status |
| `capabilities` | `std_msgs/msg/String` | Detected phone capabilities |
| `imu` | `sensor_msgs/msg/Imu` | Accelerometer, gyroscope, orientation |
| `gps` | `sensor_msgs/msg/NavSatFix` | Location, when available and permitted |
| `battery` | `sensor_msgs/msg/BatteryState` | Event-driven battery state |
| `camera/image/compressed` | `sensor_msgs/msg/CompressedImage` | JPEG camera frames |
| `camera/camera_info` | `sensor_msgs/msg/CameraInfo` | Camera calibration and image dimensions |
| `environment/illuminance` | `sensor_msgs/msg/Illuminance` | When supported by phone hardware |
| `environment/pressure` | `sensor_msgs/msg/FluidPressure` | When supported by phone hardware |
| `environment/temperature` | `sensor_msgs/msg/Temperature` | When supported by phone hardware |
| `environment/relative_humidity` | `sensor_msgs/msg/RelativeHumidity` | When supported by phone hardware |
| `motion/steps` | `std_msgs/msg/UInt32` | When the phone provides a step counter |
| `audio/raw` | `std_msgs/msg/Int16MultiArray` | Microphone samples when audio capture is enabled |
| `test/in` | `std_msgs/msg/String` | Inbound test/control topic |
| `flashlight` | `std_msgs/msg/String` | Inbound flashlight command |
| `speaker/speech` | `std_msgs/msg/String` | Inbound text-to-speech command |

Hardware and Android permissions determine which sensor topics provide data. Start the sensor runtime and enable the relevant module before expecting sensor messages.

## Repository layout

- `app/` — Android application, Java/Kotlin sources, JNI bridge, and packaged native libraries.
- `asr_ros2/` — optional ROS 2 host-side USB link and sensor-flow monitor.
- `docs/android-dds-backend-comparison.md` — recorded Cyclone DDS and Fast DDS observations and validation status.
- `scripts/` — maintainer-oriented Android ROS 2 native cross-build scripts.

## Release and licensing

This repository is being prepared for an open-source release. **Do not redistribute it until a project license is selected and the license/notice obligations for the vendored source and packaged native libraries are documented.** The current ROS 2 helper package metadata still says `Proprietary` and uses a placeholder maintainer contact. Those fields must also be resolved before release.
