# Android DDS backend comparison

Cyclone DDS is the selected Android DDS backend based on the device tests recorded below. The `fastdds` flavor is retained as a comparison build. Both variants have distinct app names and application IDs so they can be installed side by side. Run only one variant's sensor runtime at a time because both use the same ROS 2 node name.

## What is included

- Eclipse Cyclone DDS 11.0.1, built for `arm64-v8a` with Android API 28.
- ROS 2 Lyrical `rmw_cyclonedds_cpp` 4.1.5, cross-built against the existing Android ROS 2 prefix.
- Runtime Cyclone XML selects the IPv4 addresses for the enabled Wi-Fi and/or USB interfaces. The two paths can be selected independently or together.
- The Cyclone flavor has `minSdk 28`; the Fast DDS flavor keeps its current minimum SDK.

The native libraries are staged under `app/src/cyclone/jniLibs/arm64-v8a`. The source and intermediate build are kept outside Pixi under `/tmp/asr-cyclone-android`. No Pixi packages or environment files are changed.

## Build and install

The Android Cyclone and RMW libraries were built with NDK `28.2.13676358` and the existing local Android ROS 2 prefix.

```bash
./gradlew :app:assembleFastddsDebug :app:assembleCycloneDebug
adb install -r app/build/outputs/apk/cyclone/debug/app-cyclone-debug.apk
```

The Cyclone flavor appears as **ASR Runtime (Cyclone DDS)** and the comparison build appears as **ASR Runtime (Fast DDS)**.

## Observed comparison

Cyclone DDS has been more reliable in the observed Mac tests. With Cyclone, USB peer discovery completed in about 2 seconds and Wi-Fi in about 3 seconds. A direct ROS 2 subscriber received IMU, camera info, compressed images, battery, illuminance, status, and audio over Wi-Fi; the USB helper received IMU, compressed images, battery, and status. The persistent USB helper was needed for the USB host validation, while Wi-Fi was checked directly without the helper.

Earlier Fast DDS tests showed intermittent peer status that changed when short-lived ROS 2 tools or Foxglove/RQT were started and stopped. The observations were collected across separate sessions rather than a controlled benchmark, so discovery times are indicative, not a guarantee or a statistically controlled comparison.

The app's Wi-Fi DDS toggle does not require the host helper. The `asr_ros2` package is intentionally USB-only and is used for persistent USB host discovery and message diagnostics. For Wi-Fi checks, use an ordinary ROS 2 subscriber or the robot's existing ROS 2 node. Keep ROS domain ID equal across phone and host.

## Validation status

- Cyclone Android debug APK built and launched on the connected Xiaomi phone.
- USB-only and Wi-Fi-only Cyclone DDS discovery both observed below 10 seconds on Mac.
- Sensor messages were received on both paths; Wi-Fi audio required a best-effort subscriber QoS, matching the publisher's offered QoS.
- Ubuntu live validation remains outstanding.
- Simultaneous USB and Wi-Fi operation was not established as a release requirement and still needs separate validation before being documented as supported.
