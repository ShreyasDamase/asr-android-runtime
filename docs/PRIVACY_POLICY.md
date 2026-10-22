# ASR Android Runtime Privacy Policy

**Effective date:** Set this date before publication.

ASR Android Runtime turns an Android phone into a configurable sensor node for ROS 2 and ASR Studio. You choose which sensor modules to enable and which connection paths to use.

## Information the app accesses

Depending on the modules you select and the hardware available, the app can access camera frames, microphone samples, precise location, motion and IMU readings, environmental sensor readings, and battery state. Android permissions gate sensitive sensor access. You can leave modules disabled and revoke permissions in Android settings.

The app stores preferences on the device, including sensor selection and transport settings. It creates a persistent random app-scoped node identifier and derives a one-byte node ID from it. The app does not read Android ID to create this node ID. The derived node ID is included in outgoing protocol messages. Connection handshakes also include the device model, Android API level, app/protocol versions, and available sensor capabilities.

## How information is transmitted

While the runtime is active, sensor, capability, and diagnostic messages are sent to destinations configured by you. If multiple transports are enabled, messages may be sent through each active path:

- **ROS 2 DDS over Wi-Fi:** to ROS 2 peers reachable on the selected Wi-Fi network.
- **ROS 2 DDS over USB:** through Android USB tethering as an IP connection to peers reachable on that link. This is not a USB serial/UART connection.
- **TCP over Wi-Fi:** to the ASR Studio host and port configured in the app.
- **TCP over USB/ADB:** TCP through an ADB reverse tunnel to the connected computer.

Traffic may leave your local network depending on the destination and network setup. ASR Android Runtime does not provide a developer-operated cloud relay or account service. The configured ROS 2 peer or ASR Studio installation receives data directly and its operator may process or retain it under their own practices. The app's TCP connection currently uses plain TCP without TLS, and the inspected DDS configurations do not enable DDS Security. Data may be observable to parties able to inspect traffic on the route. Only connect to destinations and networks you trust.

## Collection by the app developer

The app has no account sign-in, advertising, or analytics SDK configured in its declared app dependencies. The app developer does not operate a service that receives sensor streams. Data sent to a destination you configure is handled by that destination and its operator.

The Android app does not include a contribution link or payment flow, and ASR does not collect payment details or transaction information. The ASR website may link to Buy Me a Coffee; if you follow that link, Buy Me a Coffee and its payment providers handle information you submit under their own privacy terms. See the [Buy Me a Coffee Privacy Policy](https://buymeacoffee.com/privacy-policy).

## Storage and deletion

Sensor streams are processed for live publishing and are not saved by the app as a media archive. Preferences and the node identifier remain on the device until changed, app data is cleared, or the app is uninstalled. The app cannot control retention at a DDS or TCP destination.

## Your choices

You can select sensor modules, enable or disable transports, stop the runtime, and revoke Android permissions through system settings. Disabling a module or stopping the runtime stops its live sensor publishing. You can clear locally stored preferences and the node identifier by clearing app data or uninstalling the app.

## Children and contact

ASR Android Runtime is a developer tool and is not designed for children. For privacy questions, contact the maintainer through the [project repository](https://github.com/ShreyasDamase/asr-android-runtime).

## Policy changes

This policy will be updated when the app's data practices change. The published copy and effective date should be updated together.
