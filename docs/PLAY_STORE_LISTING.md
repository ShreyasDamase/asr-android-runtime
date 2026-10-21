# Google Play listing draft

## App details

- **App name:** ASR Android Runtime
- **Category:** Tools
- **Variant choice:** Cyclone DDS (`org.asr.runtime`) is the intended Play listing package. Build and upload the Cyclone flavor; Fast DDS remains a separate build flavor for later use and must not be uploaded under the same listing unless deliberately selected.
- **Short description (under 80 characters):** Turn your Android phone into a configurable ROS 2 sensor node.

## Full description

Connect an Android phone to a robotics system as a configurable ROS 2 sensor node.

ASR Android Runtime publishes data from the sensor modules you choose. Enable ROS 2 DDS over Wi-Fi or USB tethering, or TCP to ASR Studio over Wi-Fi or USB through an ADB reverse tunnel. Configure the connection and start the runtime when you are ready.

**Available modules**
- Camera image and camera information
- Accelerometer and gyroscope (IMU)
- GPS location
- Battery state
- Environment sensors available on your device
- Microphone audio and motion sensors, when selected and supported

Sensor availability depends on the phone. Android asks for sensitive permissions when you enable a module that needs them. You can change module selection at any time.

ASR Android Runtime is intended for ROS 2 developers, robotics researchers, and makers. It is an open-source companion for robotics workflows and does not require an ASR account.

Sensor data is transmitted to the ROS 2 peers and/or ASR Studio endpoint you configure; enabled transports can operate at the same time. DDS over USB uses USB tethering as an IP connection. TCP over USB uses ADB reverse tunneling; this app does not provide a separate USB serial/UART mode. The current app transports do not encrypt these connections. Choose trusted destinations and review the privacy policy before enabling sensor streams.

The About screen currently links to an optional external Buy Me a Coffee contribution page. The app does not include checkout or receive payment details. Before including this link in a Play-distributed binary, confirm that the exact contribution flow is permitted by the current Google Play payments policy; otherwise remove the in-app link for the Play build. The website support link is separate.

## Store assets

- **Launcher icon:** present at `app/src/main/ic_launcher-playstore.png`; check Play Console's required dimensions and safe zone before upload.
- **Phone screenshots:** still need to be captured from the approved release UI. Capture the dashboard in both light and dark themes and include the expanded sensor controls and connection status.
- **Feature graphic (1024 × 500):** not present in this repository; prepare after screenshot selection so it matches the final listing.
- **Privacy policy URL (planned):** `https://shreyasdamase.github.io/asr-android-runtime/privacy.html`. Enable GitHub Pages and publish the reviewed policy before using this URL in Play Console or the app.

## Play Console items

- Content rating questionnaire and target audience declarations: not submitted or confirmed.
- Data Safety: the maintainer reports the form has passed; check the saved declaration against the final app and policy before publication.
- Store listing and publication: not submitted.
- 16 KB page-size compatibility: blocked in this repository by bundled native libraries; resolve in the separate native-library repository before uploading an APK/AAB.
