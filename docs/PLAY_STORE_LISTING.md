# Google Play listing copy

Use the copy below for the Play Store listing after checking it against the exact uploaded build.

## App details

- **App name:** ASR Android Runtime
- **Category:** Tools
- **Application ID:** `org.asr.runtime`
- **Play build:** Cyclone DDS variant. Fast DDS is not part of this release listing.
- **Release version:** `1.0.2` (version code `3`).
- **Short description (under 80 characters):** Turn your Android phone into a configurable ROS 2 sensor node.

## Full description — copy into Play Console

Turn your Android phone into a configurable sensor node for ROS 2 robotics.

ASR Android Runtime publishes live sensor data to ROS 2 peers or ASR Studio destinations that you configure. Choose the sensor groups you need, select a connection, and start the runtime when you are ready.

**Use your phone's sensors in robotics projects**

• Camera images
• Microphone audio
• GPS location
• Accelerometer, gyroscope, and motion readings
• Environmental sensors available on your device
• Battery state

Sensor availability depends on your device. Camera, microphone, and location features require Android permissions. You control which supported sensor groups are enabled.

**Connect your way**

• ROS 2 DDS over Wi-Fi
• ROS 2 DDS over USB tethering
• TCP to ASR Studio over Wi-Fi
• TCP to ASR Studio over USB using an ADB reverse tunnel

ASR sends enabled streams directly to the ROS 2 peers or ASR Studio endpoint you configure. The app does not use an ASR-operated cloud relay or account service. Connections are intended for your local robotics setup; your chosen destination or network routing can carry traffic beyond your local network. Current DDS and TCP transports are not encrypted, so connect only to destinations and networks you trust. Read the privacy policy before enabling sensor streams.

Built for ROS 2 developers, robotics researchers, students, and makers. ASR Android Runtime is open source and does not require an ASR account.

## Store assets and submission notes

- Launcher icon: `app/src/main/ic_launcher-playstore.png`.
- Phone screenshots and feature graphic: see `docs/playstore/play_store_assets/`; confirm they match the exact release UI before upload.
- Privacy policy URL: `https://shreyasdamase.github.io/asr-android-runtime/privacy.html`.
- Keep the Play Data Safety answers consistent with the shipped app's sensor streams, configured recipients, and lack of transport encryption. A local destination does not make the stream encrypted, and the public Burf listing does not reveal its private IARC/Data Safety questionnaire responses.
- Content-rating responses must describe ASR's own features. Do not copy another app's private questionnaire answers; its public age rating is not its answer sheet.
