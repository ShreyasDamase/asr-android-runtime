# ASR Android Runtime

ASR Android Runtime turns an Android phone into a ROS 2 sensor node. It publishes phone sensor data over DDS and can stream telemetry to ASR Studio over TCP. These are separate transports with separate controls: starting the sensor runtime does not automatically enable TCP or DDS.

The primary DDS backend is Cyclone DDS. A Fast DDS build is retained as a comparison variant. The Android application and ROS 2 USB monitor are maintained here; the monitor is also available as a standalone repository: [asr-ros2-link-monitor](https://github.com/ShreyasDamase/asr-ros2-link-monitor).

## Capabilities

- Camera, IMU, GPS, battery, environment, microphone, speaker, and flashlight modules, subject to device hardware and Android permissions.
- ROS 2 topics under a device-specific `/asr_android_<device-id>` namespace.
- TCP telemetry to ASR Studio on port `4242`.
- Wi-Fi DDS and USB-tether DDS. The optional host monitor is for USB DDS diagnostics only; Wi-Fi DDS uses an ordinary ROS 2 subscriber or robot node.

## Downloads

| Version | Asset | Description |
| ------- | ----- | ----------- |
| 1.0.0 | [ASR Android Runtime APK](https://github.com/ShreyasDamase/asr-android-runtime/releases/latest) | Official Android APK — install directly on your phone, no build required |
| 1.0.0 | [ros2_android_install-v1.0.0.zip](https://github.com/ShreyasDamase/asr-android-runtime/releases/latest) | Pre-built Android ROS 2 prefix — required only if building from source |

## Requirements

- Android Studio, Android SDK API 36, Android NDK 28, and CMake 3.22.1.
- An `arm64-v8a` Android device. Cyclone DDS requires Android API 28 or later; the Fast DDS variant supports API 26 or later.
- **For a source build:** the Android ROS 2 prefix (`ros2_android_install-v1.0.0.zip` from Releases). Extract it and add its path to `local.properties`:
  ```properties
  ROS2_ANDROID_PREFIX=/path/to/ros2_android_install
  ```
  Gradle reads this automatically. See the detailed [Native Build Prefix Guide](docs/BUILD_PREFIX_GUIDE.md) for full setup and troubleshooting.
- For the Play candidate, use a ROS 2 host with Cyclone DDS (`rmw_cyclonedds_cpp`) and the same `ROS_DOMAIN_ID` as the phone. The tested default is domain `0`.

## Build

From the repository root, build the Cyclone variant:

```bash
./gradlew :app:assembleCycloneDebug
```

For the Play upload bundle, configure the release signing values described in [`docs/PLAY_STORE_RELEASE_STATUS.md`](docs/PLAY_STORE_RELEASE_STATUS.md), then run:

```bash
./gradlew :app:bundleCycloneRelease
```

The bundle is written to `app/build/outputs/bundle/cycloneRelease/app-cyclone-release.aab`. Without release signing configured, Gradle creates an unsigned bundle that Play Console cannot accept as an upload artifact.

The Fast DDS comparison variant can be built with:

```bash
./gradlew :app:assembleFastddsDebug
```

Both DDS flavors use the same application ID, `org.asr.runtime`, and cannot be installed side by side. Install or upload one flavor at a time. Do not run both sensor runtimes simultaneously on one device; they use the same ROS 2 node name.

The current Android version is `1.0.2` (version code `3`); the Cyclone build adds the `-cyclone` suffix. The app displays its selected DDS backend and exact build version beneath the app name.

## Start the phone runtime

1. Install and open **ASR Android Runtime**. Choose the Cyclone DDS build for the primary configuration.
2. Grant permissions for the sensors you intend to use.
3. Start **Runtime**. This starts the phone sensor services.
4. Enable the desired transport separately: **Wi-Fi DDS**, **USB DDS**, or the **TCP · ASR Studio** connection.
5. Enable the sensor modules required by your application. Available data depends on the phone hardware, permissions, and module state.

## Connect to ASR Studio over TCP

TCP is for the ASR Studio desktop application; it is independent of ROS 2 DDS.

### Wi-Fi TCP

1. Put the phone and computer on the same reachable network.
2. In ASR Studio, open **Devices**, select **Wi-Fi**, enter the computer's local IP, then choose **Listen (Server)**.
3. In the Android app's TCP section, enable the connection and enter the computer IP with port `4242`.

### USB / ADB TCP

1. Connect the phone over USB and authorize USB debugging on the computer.
2. In ASR Studio, open **Devices**, select **USB / ADB**, and press **Auto-Configure USB / ADB (1-Click Setup)**. This runs `adb reverse tcp:4242 tcp:4242` for you.
3. Choose **Listen (Server)** in ASR Studio.
4. Enable TCP in the Android app and use host `127.0.0.1`, port `4242`.

USB / ADB TCP forwarding does not enable USB DDS. Configure USB tethering separately for DDS.

## Connect to ROS 2 DDS

The phone uses Cyclone DDS. The documented host configuration uses ROS 2 Lyrical and `rmw_fastrtps_cpp`; the host and Android participant interoperate over the selected network.

### Wi-Fi DDS

1. Connect the phone and ROS 2 host to the same Wi-Fi network that permits peer-to-peer DDS discovery.
2. Set matching `ROS_DOMAIN_ID` on both sides (default `0`) and use `rmw_fastrtps_cpp` on the host.
3. Start phone runtime, enable **Wi-Fi DDS**, and enable the required sensors. The phone will display **"Waiting for discovery"** until a ROS 2 participant or subscriber is active on the network.
4. Discover phone topics using your robot node or standard ROS 2 tools (`ros2 topic list`, `ros2 topic echo`). The USB Link Monitor is USB-only and is not needed for Wi-Fi DDS.

### USB-tether DDS

1. Connect the phone to the ROS 2 host and enable Android **USB tethering** in system settings.
2. Start the phone runtime and enable **USB DDS**. For an isolated USB check, leave Wi-Fi DDS off.
3. In a sourced ROS 2 host environment using `rmw_fastrtps_cpp` and the same domain, run the persistent USB diagnostic:

   ```bash
   ros2 launch asr_ros2 connect.launch.py transport:=usb
   ```

4. If the host interface cannot be selected automatically, pass its name. For example, some macOS USB-tether setups use `feth0`:

   ```bash
   ros2 launch asr_ros2 connect.launch.py transport:=usb interface:=feth0
   ```

This monitor is optional diagnostic software: it reports graph visibility and actual sensor callbacks separately. It does not start Android runtime or enable tethering. Raspberry Pi use is expected with a compatible ROS 2 installation and USB-tether interface, but has not yet been validated on-device.

The standalone package installation and detailed diagnostic interpretation are documented in [asr-ros2-link-monitor](https://github.com/ShreyasDamase/asr-ros2-link-monitor). A copy is also present under [`asr_ros2/`](asr_ros2/README.md).

### View topics in Foxglove

Foxglove connects to a **Foxglove Bridge** WebSocket. The ASR USB Link Monitor does not start this bridge; it is a separate ROS 2 participant used for discovery and diagnostics. Keep the USB monitor running, then launch the bridge in a second terminal using the same sourced ROS 2 environment, `ROS_DOMAIN_ID`, and host RMW:

```bash
ros2 launch foxglove_bridge foxglove_bridge_launch.xml
```

In Foxglove, choose **Open a new connection → Foxglove WebSocket** and enter `ws://localhost:8765` when it runs on the ROS 2 host. Check the Data source topic list and open a live ASR topic to verify messages. The bridge must see the same ROS 2 graph as the monitor. For a Foxglove client on another computer, use the bridge host's reachable address and allow port `8765` through the host firewall. If the launch reports that `foxglove_bridge` is missing, build or install it in the sourced ROS 2 environment. See the [Foxglove ROS 2 guide](https://docs.foxglove.dev/docs/getting-started/frameworks/ros2) for bridge setup and connection details.

## ROS 2 topic reference

Topics are relative to `/asr_android_<device-id>`. For example, a device with ID `168` publishes IMU data on `/asr_android_168/imu`. Sensor availability and message rates depend on hardware, permissions, and module state.

| Topic suffix | ROS 2 type | Description |
| --- | --- | --- |
| `status` | `std_msgs/msg/String` | Runtime status heartbeat |
| `capabilities` | `std_msgs/msg/String` | Available device capabilities |
| `imu` | `sensor_msgs/msg/Imu` | Orientation and inertial data |
| `gps` | `sensor_msgs/msg/NavSatFix` | Location, when available and permitted |
| `battery` | `sensor_msgs/msg/BatteryState` | Event-driven battery state |
| `camera/image/compressed` | `sensor_msgs/msg/CompressedImage` | JPEG camera frames |
| `camera/camera_info` | `sensor_msgs/msg/CameraInfo` | Camera calibration and image dimensions |
| `environment/illuminance` | `sensor_msgs/msg/Illuminance` | Ambient light, when supported |
| `environment/pressure` | `sensor_msgs/msg/FluidPressure` | Pressure, when supported |
| `environment/temperature` | `sensor_msgs/msg/Temperature` | Temperature, when supported |
| `environment/relative_humidity` | `sensor_msgs/msg/RelativeHumidity` | Humidity, when supported |
| `motion/steps` | `std_msgs/msg/UInt32` | Step count, when supported |
| `audio/raw` | `std_msgs/msg/Int16MultiArray` | Microphone samples when audio capture is enabled |
| `test/in` | `std_msgs/msg/String` | Inbound test/control message |
| `flashlight` | `std_msgs/msg/String` | Inbound flashlight command |
| `speaker/speech` | `std_msgs/msg/String` | Inbound text-to-speech command |

The helper currently monitors `imu`, `camera/image/compressed`, `battery`, and `status`; it does not subscribe to every topic in this table.

## Validation status

Cyclone DDS was exercised on macOS over Wi-Fi and USB tethering, with ROS graph discovery and sensor delivery observed. These results describe the tested setup, not a guarantee for every network. Ubuntu and Raspberry Pi live transport validation remain outstanding. See [`docs/android-dds-backend-comparison.md`](docs/android-dds-backend-comparison.md) for recorded comparisons and limits.

## Repository layout

- `app/` — Android application, JNI bridge, and packaged native libraries.
- `asr_ros2/` — ROS 2 USB link and sensor-flow monitor.
- `docs/` — transport validation, [Native Build Prefix Guide](docs/BUILD_PREFIX_GUIDE.md), and release checklists.
- `scripts/` — maintainer tools and prefix packaging (`package_prefix.sh`).

## Source availability and licensing

This project is maintained by [Shreyas Damase](https://github.com/ShreyasDamase).

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details. Third-party licenses and attribution notices for native ROS 2, Cyclone DDS, Fast DDS, and Android Jetpack libraries are provided in the [NOTICE.txt](NOTICE.txt) file.

## ASR platform website

The public-facing website is maintained in [`website/`](website/). It includes platform information, download and documentation links, and readable privacy, license, and third-party notice pages. GitHub Pages deployment is configured in [`.github/workflows/pages.yml`](.github/workflows/pages.yml); enable **Settings → Pages → Build and deployment → GitHub Actions** to publish it from `main`. The default project site URL is `https://shreyasdamase.github.io/asr-android-runtime/`; no custom domain is needed.
