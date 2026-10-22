# ASR Android Runtime Privacy Policy

**Effective date: 23 October 2026**

ASR Android Runtime turns an Android phone into a configurable sensor node for ROS 2 and ASR Studio. You choose which sensor modules to enable and which connection paths and destinations to use.

## Information accessed

Depending on the modules you enable and the hardware available, the app can access camera frames, microphone samples, precise location, motion and IMU readings (including a device-provided step-counter reading where supported), environmental sensor readings, and battery state. Android permissions gate sensitive sensor access. You can leave modules disabled and revoke permissions in Android settings.

The app stores preferences on your device, including sensor selection and transport settings. It creates a persistent random app-scoped node identifier and derives a one-byte node ID from it; it does not read Android ID to create this node ID. Connection messages sent to your configured destination may also include the derived node ID, device model, Android API level, app and protocol versions, and available sensor capabilities.

## Direct connections to your robotics system

ASR is designed to publish directly to robotics systems that you configure, not to an ASR-operated cloud service. While the runtime is active, selected sensor streams and connection metadata are sent over only the transport paths you enable. If you enable more than one path, the same selected data may be sent over each active path:

- **ROS 2 DDS over Wi-Fi:** to ROS 2 peers reachable on the Wi-Fi network you select.
- **ROS 2 DDS over USB:** through Android USB tethering as an IP connection to peers reachable on that link. This is not a USB serial/UART connection.
- **TCP over Wi-Fi:** to the ASR Studio host and port you configure.
- **TCP over USB/ADB:** TCP through an ADB reverse tunnel to the connected computer.

These connections are intended for your local robotics setup. The destination you configure receives the data directly, and its operator may process or retain it under their own practices. Network routing or a destination you configure can carry traffic beyond your local network, so check the endpoint and route. ASR does not operate a cloud relay, account service, or server that receives sensor streams.

## Security

The current TCP connection uses plain TCP without TLS, and DDS Security is not enabled. Data may be visible to parties able to inspect traffic on the route. Use trusted destinations and networks. The app does not claim that its transport connections are encrypted.

## Data received by the ASR developer

The ASR developer does not receive sensor streams through an ASR-operated backend. The app has no account sign-in, advertising, or analytics service. This is separate from the direct transfer to a ROS 2 peer or ASR Studio endpoint that you configure.

The Android app does not include a contribution link or payment flow, and ASR does not receive payment details or transaction information. The ASR website may link to Buy Me a Coffee. If you follow that link, Buy Me a Coffee and its payment providers handle information you submit under their own privacy terms. See the [Buy Me a Coffee Privacy Policy](https://buymeacoffee.com/privacy-policy).

## Storage, retention, and deletion

Sensor streams are processed for live publishing and are not saved by the app as a media archive. Preferences and the node identifier remain on your device until changed, app data is cleared, or the app is uninstalled. The app cannot control retention at a DDS or TCP destination.

## Your choices

You can select sensor modules, enable or disable transports, stop the runtime, and revoke Android permissions through system settings. Disabling a module or stopping the runtime stops its live sensor publishing. You can clear locally stored preferences and the node identifier by clearing app data or uninstalling the app.

## Children, contact, and policy changes

ASR Android Runtime is a robotics developer tool and is not designed for children. For privacy questions, contact the maintainer through the [project repository](https://github.com/ShreyasDamase/asr-android-runtime). This policy will be updated if the app's data practices change; the published policy and effective date should be updated together.
