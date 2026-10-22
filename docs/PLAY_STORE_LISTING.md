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

Put your Android phone to work as a sensor node for your ROS 2 robot.

ASR Android Runtime reads the sensor groups you enable and sends their data to the robotics system you configure. With DDS, sensor readings are published as ROS 2 topics for ROS 2 peers on your network to discover and subscribe to. With TCP, sensor streams are sent to ASR Studio.

**Choose the sensors your project needs**

• Camera images
• Microphone audio
• GPS location
• Accelerometer and gyroscope readings
• Motion and environmental sensor data
• Battery state

Sensor availability depends on your device. Camera, microphone, and location features require Android permissions. You control which supported sensor groups are enabled.

**Choose a connection**

• ROS 2 DDS over Wi-Fi
• ROS 2 DDS over USB tethering
• TCP to ASR Studio over Wi-Fi
• TCP to ASR Studio over USB using an ADB reverse tunnel

DDS connects your phone to ROS 2 peers. TCP connects your phone to ASR Studio; it is a separate transport from DDS. Configure the destination you want to use for either connection. The app sends data directly to that destination and does not use an ASR-operated cloud relay or account service.

These connections are designed for robotics setups on a local network. Sensor streams go directly to the destination you configure, giving you control over where the data is sent. For sensitive data, choose a trusted network and endpoint. Read the privacy policy before enabling sensor streams.

Built for ROS 2 developers, robotics researchers, students, and makers. ASR Android Runtime is open source and does not require an ASR account.

## Store assets and submission notes

- Launcher icon: `app/src/main/ic_launcher-playstore.png`.
- Phone screenshots and feature graphic: see `docs/playstore/play_store_assets/`; confirm they match the exact release UI before upload.
- Privacy policy URL: `https://shreyasdamase.github.io/asr-android-runtime/privacy.html`.
- Keep the Play Data Safety answers consistent with the shipped app's sensor streams, configured recipients, and lack of transport encryption. A local destination does not make the stream encrypted, and the public Burf listing does not reveal its private IARC/Data Safety questionnaire responses.
- Content-rating responses must describe ASR's own features. Do not copy another app's private questionnaire answers; its public age rating is not its answer sheet.
