# ASR Android Runtime

ASR Android Runtime turns an Android phone into a ROS 2 sensor node. It publishes phone sensor data over DDS and can stream telemetry to ASR Studio over TCP. These are separate transports with separate controls: starting the sensor runtime does not automatically enable TCP or DDS.

The primary DDS backend is Cyclone DDS. A Fast DDS build is retained as a comparison variant. The Android application and ROS 2 USB monitor are maintained here; the monitor is also available as a standalone repository: [asr-ros2-link-monitor](https://github.com/ShreyasDamase/asr-ros2-link-monitor).

## Capabilities

- Camera, IMU, GPS, battery, environment, microphone, speaker, and flashlight modules, subject to device hardware and Android permissions.
- ROS 2 topics under a device-specific `/asr_android_<device-id>` namespace.
- TCP telemetry to ASR Studio on port `4242`.
- Wi-Fi DDS and USB-tether DDS. Plain ROS 2 tools and nodes find the phone on both paths. The optional host monitor adds USB DDS diagnostics.

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
3. Start phone runtime, enable **Wi-Fi DDS**, and enable the required sensors. The phone displays **"NO OTHER NODES"** until another ROS 2 node joins the same `ROS_DOMAIN_ID`. That is normal: discovery is already running, and the phone's node can be found by `ros2 node list`.
4. Discover phone topics using your robot node or standard ROS 2 tools (`ros2 topic list`, `ros2 topic echo`). The USB Link Monitor is USB-only and is not needed for Wi-Fi DDS.

### USB-tether DDS

1. Connect the phone to the ROS 2 host and enable Android **USB tethering** in system settings.
2. Start the phone runtime and enable **USB DDS**. For an isolated USB check, leave Wi-Fi DDS off.
3. In a sourced ROS 2 host environment using `rmw_fastrtps_cpp` and the same domain, check the phone with plain ROS 2 tools:

   ```bash
   ros2 node list
   ros2 topic list
   ```

4. Optional: run the persistent USB diagnostic for ongoing health reports:

   ```bash
   ros2 launch asr_ros2 connect.launch.py transport:=usb
   ```

5. If the monitor cannot select the host interface automatically, pass its name. For example, some macOS USB-tether setups use `feth0`:

   ```bash
   ros2 launch asr_ros2 connect.launch.py transport:=usb interface:=feth0
   ```

The monitor is not needed to make the phone visible: in Mac tests with the Cyclone build, plain `ros2 node list` and `ros2 topic echo` worked over USB tether with no monitor running. It is optional diagnostic software: it reports graph visibility and actual sensor callbacks separately. It does not start Android runtime or enable tethering. Raspberry Pi use is expected with a compatible ROS 2 installation and USB-tether interface, but has not yet been validated on-device.

The standalone package installation and detailed diagnostic interpretation are documented in [asr-ros2-link-monitor](https://github.com/ShreyasDamase/asr-ros2-link-monitor). A copy is also present under [`asr_ros2/`](asr_ros2/README.md).

### Wi-Fi routers that block multicast

Some routers don't pass multicast between devices. Ping works, but `ros2 node list` shows nothing and the phone stays on **NO OTHER NODES**. Point both sides at each other:

1. On the phone, press **EDIT** and set `ROS_STATIC_PEERS` to the computer's IP address.
2. On the computer, set the phone's IP address before running ROS 2 commands:

   ```bash
   export ROS_STATIC_PEERS=192.168.1.5
   ```

### Locked screens

The node keeps publishing while the phone is locked, because the app holds wake and Wi-Fi locks whenever the runtime or DDS is on. For a phone mounted on a robot, also exempt the app from battery optimization, since some phones stop background apps anyway.

### Configure the phone remotely

Turn on **REMOTE CONFIG** on the phone. It is off by default, and it also controls `flashlight` and `speaker/speech`. Unless SROS2 is on, any node on the same `ROS_DOMAIN_ID` can then send these commands.

```bash
ros2 topic echo --qos-durability transient_local /asr_android_250/config/state
ros2 topic pub --once /asr_android_250/config/set std_msgs/msg/String "{data: camera.resolution=640x480}"
```

| Key | Values |
| --- | --- |
| `camera.resolution` | `320x240`, `640x480` (default), `1280x960` |
| `camera.id` | `auto`, or an ID from `camera.available` in `config/state` |
| `camera.calibration` | `WxH,fx,fy,cx,cy,k1,k2,t1,t2,k3`, or `clear` |
| `imu.rate_hz` | `0` to `1000`; `0` publishes every sample |
| `sensors.<name>` | `true` or `false` for `camera`, `imu`, `environment`, `motion`, `audio`, `battery`, `gps` |

`ROS_DOMAIN_ID`, node name and `ROS_STATIC_PEERS` can only be changed on the phone. Use `ROS_STATIC_PEERS` on networks that block multicast.

Remote config uses topics because, in Mac tests, a Fast DDS client got no reply from a Cyclone DDS service, so `ros2 param` cannot reach the phone directly. Python examples, including a node that offers real ROS 2 parameters on the host, are in the `asr_android_examples` package.

### Secure the connection with SROS2

SROS2 encrypts traffic and only allows nodes that hold keys from your keystore. It is off by default. In Mac tests, a client without keys received nothing from a secured phone, and a client with keys received 27.9 FPS and 394 Hz IMU over Wi-Fi.

The Fast DDS in the tested Pixi environment was built without security, so secured nodes on that host use Cyclone DDS (`rmw_cyclonedds_cpp`). Check your own host with a test before relying on it.

1. Create a keystore and enclaves on the computer. Keep the `private` folder off the phone.

   ```bash
   ros2 security create_keystore ~/asr_keystore
   ros2 security create_enclave ~/asr_keystore /asr_android
   ros2 security create_enclave ~/asr_keystore /host
   ```

2. Copy only the phone's enclave files. The app moves them into private storage the next time SROS2 is checked or the node starts.

   ```bash
   E=/sdcard/Android/data/org.asr.runtime/files/ros2_keystore/enclaves/asr_android
   adb shell mkdir -p $E
   for f in identity_ca.cert.pem cert.pem key.pem permissions_ca.cert.pem governance.p7s permissions.p7s; do adb push ~/asr_keystore/enclaves/asr_android/$f $E/; done
   ```

3. Turn on **ROS_SECURITY_ENABLE** on the phone.

4. On the computer, set these before running ROS 2 commands:

   ```bash
   export RMW_IMPLEMENTATION=rmw_cyclonedds_cpp
   export ROS_SECURITY_KEYSTORE=~/asr_keystore
   export ROS_SECURITY_ENABLE=true
   export ROS_SECURITY_STRATEGY=Enforce
   export ROS_SECURITY_ENCLAVE_OVERRIDE=/host
   ```

If keys are missing while the switch is on, the node doesn't start. It never falls back to an unsecured connection.

### View topics in Foxglove

Foxglove connects to a **Foxglove Bridge** WebSocket. The ASR USB Link Monitor does not start this bridge; it is a separate, optional diagnostic participant. Launch the bridge using the same sourced ROS 2 environment, `ROS_DOMAIN_ID`, and host RMW:

```bash
ros2 launch foxglove_bridge foxglove_bridge_launch.xml
```

In Foxglove, choose **Open a new connection → Foxglove WebSocket** and enter `ws://localhost:8765` when it runs on the ROS 2 host. Check the Data source topic list and open a live ASR topic to verify messages. The bridge must see the same ROS 2 graph as the monitor. For a Foxglove client on another computer, use the bridge host's reachable address and allow port `8765` through the host firewall. If the launch reports that `foxglove_bridge` is missing, build or install it in the sourced ROS 2 environment. See the [Foxglove ROS 2 guide](https://docs.foxglove.dev/docs/getting-started/frameworks/ros2) for bridge setup and connection details.

## ROS 2 topic reference

Topics are relative to `/asr_android_<device-id>`. For example, a device with ID `168` publishes IMU data on `/asr_android_168/imu`. Sensor availability and message rates depend on hardware, permissions, and module state. `config/state`, `config/set` and the global `/tf_static` are also available.

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
