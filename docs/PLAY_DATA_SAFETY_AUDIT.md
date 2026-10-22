# Play Data Safety audit worksheet

Internal release worksheet. Complete and verify in Play Console separately for each application ID and shipped variant. This is an engineering inventory, not a submitted declaration or legal determination. Google Play's form and definitions control the final answers.

## Observed outgoing data

| Data observed in source | When / route | Candidate Play data type to review | Notes |
| --- | --- | --- | --- |
| Camera frames | Camera module selected and runtime active; active DDS and/or TCP transports | Photos and videos | User-selected live stream; no app media archive found. |
| Microphone PCM samples | Microphone module selected and runtime active; active DDS and/or TCP transports | Audio files / voice or sound recordings | User-selected live stream; no app audio archive found. |
| GPS coordinates and related readings | GPS module selected and runtime active; active DDS and/or TCP transports | Location, precise location | Treat as precise location based on GPS and permission. |
| Accelerometer, gyroscope, steps/motion, environment, battery | Respective module selected and runtime active; active DDS and/or TCP transports | Review Health and fitness, app activity, and other data categories as appropriate | Validate mapping against the current Play form definitions; avoid assuming that no category applies. |
| Device model, Android API, app/protocol version, sensor capabilities | Sent in transport capability handshake | Device or other IDs / app info and performance / diagnostics as appropriate | Destination receives device model and configuration capabilities. |
| One-byte node ID | Included in outgoing protocol messages | Device or other IDs | Derived from a stored random app-scoped UUID; the app no longer reads Android ID to generate it. |
| Host and port configured in app | Used to establish TCP connection | Not necessarily transmitted as a user data field | Verify whether destination/service logs connection metadata. The app has no developer-operated receiver. |
| Buy Me a Coffee contribution details | No contribution link or payment flow in the Play-distributed Android build. The website may link to the external service. | Not collected or shared by the Android app | ASR does not embed checkout or receive payment details or transaction results. Buy Me a Coffee/payment providers handle information entered on their site under their policies. Reassess if a future Android build restores an in-app contribution link or payment flow. |

## Routes and security

- DDS over Wi-Fi.
- DDS over USB tethering, using IP networking.
- TCP over Wi-Fi to configured ASR Studio host.
- TCP over USB via ADB reverse tunnel.
- Enabled transports may run concurrently and receive the same selected sensor data.
- Current TCP is plain TCP without TLS; inspected DDS configuration does not enable DDS Security. Do not claim encryption in transit.
- Code review found no separate native USB serial/UART transport. Do not describe ADB or tethering as UART.

## Questions to resolve in Play Console

- Whether the configured peers qualify as service providers or third parties for each disclosure and sharing answer. User-configured destinations do not automatically make data collection disappear from the form.
- Whether any documented user-initiated-transfer or ephemeral-processing exception applies to each data type and route. Confirm against the current form requirements; do not infer a blanket exception.
- Whether camera, audio, location, motion, device identifier, and device model/capability fields require collection declarations under the actual distribution and use.
- Whether the Data Safety encryption question should be answered “No” for this binary (current source indicates no transport encryption).
- Whether diagnostics or connection metadata are retained by the configured ASR Studio/ROS 2 endpoint and who operates those endpoints.
- Ensure the in-app disclosure, privacy policy, store listing, and saved form describe the same package and binary behavior.
- The current Play-distributed Android UI omits the Buy Me a Coffee link. Reassess payments policy and update this worksheet if a future Android build restores it.

## Evidence and release gate

Source reviewed: `PacketSerializer.capabilities`, sensor managers, `TransportManager`, `TcpTransport`, ROS 2 transport setup, and Android permission flow. Re-run this audit on the exact release commit and inspect the built artifacts and dependency manifests before submitting. A prior Play review/pass is not evidence that the current form is accurate or complete.
