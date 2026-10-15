# ASR Android Runtime privacy policy — draft

**Effective date:** to be set when this policy is reviewed and published.

ASR Android Runtime is an open-source robotics companion app. It reads sensor data only for modules that you select while the runtime is active. This draft must be reviewed and published at a stable public URL before it is used as the Play Store privacy policy.

## Data handled by the app

Depending on the modules you enable and the hardware available, the app can access camera frames, microphone samples, precise location, motion and IMU readings, environmental sensor readings, and battery state. Android permissions gate sensitive sensor access. The app also stores settings on the device, including sensor selection, transport settings, and a persistent device/node identifier used to name ROS 2 topics.

## How data is transmitted

Sensor and diagnostic messages are sent only through the transport paths you enable: ROS 2 DDS or TCP to the destination configured in the app. Depending on that destination and network configuration, traffic may leave the local network. The app does not provide a cloud relay or account service. You choose and control the network destinations; only enable destinations you trust.

## Collection by the maintainer

The app has no account sign-in, advertising, or analytics SDK configured in its declared app dependencies. The maintainer does not operate a service that receives sensor data. Data sent to a destination you configure is handled by that destination and its operator.

## Storage, retention, and deletion

Sensor streams are processed for live publishing and are not saved as a media archive by the app. App preferences remain on the device until changed or the app's data is cleared or the app is uninstalled. The app does not control retention at a DDS or TCP destination.

## Your controls

You can select sensor modules, enable or disable transports, stop the runtime, and revoke Android permissions through system settings. Revoking permission can stop the corresponding sensor module from publishing.

## Children and contact

ASR Android Runtime is a developer tool and is not designed for children. For privacy questions, contact the maintainer through the project repository: <https://github.com/ShreyasDamase/asr-android-runtime>.

## Changes

This policy may be updated as the app changes. The published version and its effective date should be updated together.
