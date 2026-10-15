# ASR Android Runtime privacy policy — draft

**Effective date:** to be set when this policy is reviewed and published.

ASR Android Runtime is an open-source robotics companion app. It reads sensor data only for modules that you select while the runtime is active. This draft must be reviewed and published at a stable public URL before it is used as the Play Store privacy policy.

## Data handled by the app

Depending on the modules you enable and the hardware available, the app can access camera frames, microphone samples, precise location, motion and IMU readings, environmental sensor readings, and battery state. Android permissions gate sensitive sensor access. The app also stores settings on the device, including sensor selection and transport settings. It creates a persistent random app-scoped identifier for the node and derives a one-byte node ID from it. The app does not read Android ID to create the node ID; the derived node ID is included in outgoing protocol messages.

## How data is transmitted

Sensor, capability, and diagnostic messages are sent through the transport paths you enable. If more than one transport is enabled, data may be sent over each active path:

- **ROS 2 DDS over Wi-Fi:** to ROS 2 peers reachable on the selected Wi-Fi network.
- **ROS 2 DDS over USB:** over Android USB tethering to peers reachable through that tethered IP link. This is an IP/DDS path, not a serial UART connection.
- **TCP to ASR Studio over Wi-Fi:** to the host and port configured in the app.
- **TCP to ASR Studio over USB/ADB:** the app still uses TCP; ASR Studio configures an ADB reverse tunnel to the connected computer.

Depending on the configured network and destination, traffic may leave the local network. The app does not provide a cloud relay or account service. The recipient is the ROS 2 peer or ASR Studio installation on the host you configure. The Android app does not send sensor streams to a developer-operated cloud service. A ROS 2 peer or ASR Studio installation receives the stream directly and may process or retain it under the operator's own practices. Only enable destinations you trust. The app's TCP connection currently uses plain TCP without TLS, and the inspected DDS configurations do not enable DDS Security; sensor data can therefore be observable to parties able to inspect traffic on the route.

## Collection by the maintainer

The app has no account sign-in, advertising, or analytics SDK configured in its declared app dependencies. Data sent to a destination you configure is handled by that destination and its operator. Enabling a destination is not a guarantee that the destination or its network is private or secure.

The About screen includes an optional **Buy Me a Coffee** link that opens the Buy Me a Coffee website outside the app. ASR does not embed Buy Me a Coffee or Stripe, collect payment details, receive transaction information, or grant app features or benefits in exchange for a contribution. Buy Me a Coffee and its payment providers handle information submitted on their site under their own privacy terms; see the [Buy Me a Coffee Privacy Policy](https://buymeacoffee.com/privacy-policy). The support link is optional and is not needed to use the app.

## Storage, retention, and deletion

Sensor streams are processed for live publishing and are not saved as a media archive by the app. App preferences, including the node identifier, remain on the device until changed or the app's data is cleared or the app is uninstalled. The app does not control retention at a DDS or TCP destination.

## Your controls

You can select sensor modules, enable or disable transports, stop the runtime, and revoke Android permissions through system settings. Revoking permission can stop the corresponding sensor module from publishing.

## Children and contact

ASR Android Runtime is a developer tool and is not designed for children. For privacy questions, contact the maintainer through the project repository: <https://github.com/ShreyasDamase/asr-android-runtime>.

## Changes

This policy may be updated as the app changes. The published version and its effective date should be updated together.

## Internal Play Console review note — remove before publication

Google Play defines Data Safety "collection" as transmitting data off the user's device. Its guidance treats a user-initiated or prominently disclosed transfer as a possible exception to declaring **sharing**; that does not automatically exempt off-device transmission from **collection**. Audit each outgoing data type and every transport before reusing a "no data collected" declaration. The Android and Cyclone/Fast DDS package variants may need separate forms because the form is per package name. The maintainer reports a previous Data Safety pass, but Google says the review does not verify declaration completeness. This note is internal and must be removed from the published policy.

The Android TCP transport currently uses a plain `java.net.Socket`, not TLS, and the DDS runtime configuration inspected for this draft does not configure DDS Security. Do not claim all user data is encrypted in transit unless each route is secured or otherwise verified. The Play Data Safety form and final policy must match the shipped app's actual transport security.

The app also links to an external voluntary contribution page. Google Play's payments policy contains exceptions for some peer-to-peer payments and donations, but the policy does not establish that every Buy Me a Coffee setup qualifies. Review the exact page flow and contribution model against the current policy before release. The conservative Play release choice is to remove/disable the in-app contribution link unless its eligibility is confirmed; the website may continue to link to it. Updating this privacy policy does not make a payment flow policy-compliant.
