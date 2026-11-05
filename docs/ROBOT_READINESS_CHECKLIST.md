# ASR Android: Robot Readiness Checklist

Key: `[ ]` not started, `[~]` in progress, `[x]` done and verified on a phone, `[?]` needs a decision, `[*]` kept in the app but varies by phone maker, so not tested on each one

Rules: branch from `develop`. No new libraries without asking. New UI matches the current UI. Never install or update the Pixi environment. Labels use standard ROS 2 terms only.

## 0. Discovery label and docs
- [x] Status uses ROS 2 terms (`DISCOVERY`, `NO OTHER NODES`, `NODES DISCOVERED`); monitor documented as optional on USB and Wi-Fi
- [x] Screen switches update when the DDS path changes without the UI
- [~] `ROS2_ENABLE` keeps the selected DDS paths instead of forcing Wi-Fi. Code built; not exercised on a phone

## 1. Correct sensor data
- [x] Timestamps use Unix time on all ROS 2 sensor messages (IMU, camera, GPS, illuminance and battery verified on phone)
- [x] IMU orientation falls back to Game Rotation Vector; verified with the phone moving
- [x] IMU covariance no longer marks gyro, accel and valid orientation as "no data"
- [x] IMU publishes about 500 Hz; `imu.rate_hz` limit added
- [x] `camera_info` uses phone-reported lens values; `K[0]=0` (uncalibrated) when the phone has none. Verified: `fx=474`, center `320,240` at 640x480. Device values, not a checkerboard calibration
- [x] Separate `frame_id` per sensor, prefixed with the node namespace
- [x] `tf_static` published with bundled `tf2_msgs` (Cyclone builds, 16 KB aligned). Verified with `tf2_echo`: camera rotation `[0.707, -0.707, 0, 0]`. Translations are zero by design

## 2. Configuration
- [~] Phone UI: `ROS_DOMAIN_ID` and node name, locked behind EDIT. Locked fields confirmed read-only on a phone; domain and rename verified through the service; tapping EDIT and APPLY needs an unlocked phone
- [x] Phone UI: `ROS_STATIC_PEERS`, locked behind EDIT. Verified on Wi-Fi where the router blocks multicast; the Mac may also need `ROS_STATIC_PEERS=<phone IP>`
- [x] Remote config over `config/set` and `config/state` topics, verified on phone. `ros2 param` to the phone doesn't work (Fast DDS client got no reply from a Cyclone service), so a Python node on the Mac offers real parameters and forwards them; verified
- [x] Sensor toggles, IMU rate limit and camera settings verified. `sensors.gps=false` stops the topic; settings survive an app restart
- [x] Camera resolution presets keep 640x480 as default, with live FPS in `config/state`. USB, back main camera: 320x240 29.7 FPS 0.4 MB/s; 640x480 29.8 FPS 1.5 MB/s; 1280x960 29.7 FPS 5.2 MB/s; 1920x1440 20.2 FPS 9.6 MB/s. Wi-Fi 640x480 29.9 FPS
- [x] Actual resolution shown in `config/state` (1280x720 requested gives 1280x960 on this phone)
- [~] Fields refresh when changed outside the screen. Code built; needs a screen check
- [~] Checkerboard override and Python `camera_calibrator` built. Needs a printed checkerboard to verify

## 3. Multiple cameras
- [x] Cameras listed in `config/state`, selected by ID, with `auto`. First phone exposes only `0` and `1` to apps; hidden IDs are rejected
- [x] Switch cameras remotely while running; front camera verified
- [x] Two streams: not possible on the test hardware. `camera.concurrent` reports supported pairs, and both tested phones report none. Streaming two at once stays unbuilt until a phone that supports it is available
- [x] Topic naming for extra camera streams: not needed until two-stream hardware exists

## 4. Robot-mounted operation
- [*] START ON BOOT: kept in the app, off by default. Auto-start rules vary by phone maker. Xiaomi's Autostart setting blocked it in the one reboot test
- [x] Locked phones keep publishing. Both locked together: USB 30.0 FPS and 498 Hz; Wi-Fi 21.2 FPS and 388 Hz. Wake and Wi-Fi locks held whenever the runtime or DDS is active
- [ ] Recovery after a link drop: code exists; needs someone to toggle Wi-Fi or unplug USB on a phone
- [x] Thermal status and battery percent in `config/state`, verified
- [ ] 8-hour run

## 5. Security
- [ ] GPS reports after re-enable or on a locked phone: no fix seen indoors in 40 s; check near a window
- [x] `AsrService` is not exported in release builds; debug keeps adb. Verified in the merged release and debug manifests
- [x] Release build verified on a phone: outside service and activity starts are refused, a broadcast starts nothing, and the launcher still opens
- [x] Other exported components reviewed: only the launcher and `MainActivity` are exported, and neither reads intent extras (code read only)
- [x] GPS coordinates and spoken text removed from logs (code changed and built)
- [x] Removed the broken `stop_asr` launcher shortcut; `MainActivity` is no longer exported in release builds
- [~] REMOTE CONFIG switch, default off. `config/set` rejection verified; on-screen tap and flashlight/speaker gating need an unlocked phone
- [x] ROS 2 command contents are no longer written to the log
- [x] SROS2 verified end to end on a phone. No-key clients can't see the secured node or its data; a client with keys got 27.9 FPS and 394 Hz over Wi-Fi; `data_protection_kind` and `metadata_protection_kind` are ENCRYPT; missing keys stop the node with no fallback. On this Mac, secured nodes must use Cyclone, because Pixi's Fast DDS has no security. The libddsc swap was rechecked with no streaming regression
- [ ] SROS2 on-screen switch needs a tap test
- [x] TLS for TCP to ASR Studio: dropped by decision. TCP stays unchanged; this work targets ROS 2
- [ ] Update privacy policy and Data Safety only after these ship and are tested

## 6. Stability and memory (last, across all features above)
- [~] Full 60-minute soak running on phone 1 from 19:03. The earlier partial run stopped at 28 minutes with stable memory
- [~] IMU and camera buffers reused and running on phones. Memory stable so far; no before and after comparison measured
- [x] Fixed native shutdown deadlock risk, a JNI reference leak and a thread detach bug. 15 start/stop cycles: threads steady at 37, native memory settled at 12.8 MB, no crashes. 600-command burst plus DDS restarts: no crash
- [x] TCP threads reviewed: disconnect interrupts and joins them (code read only)

## Future: examples workspace for students

Location: `~/Terminator/ROS2_PROJECTS/asr_android_ws` (package `asr_android_examples`)

- [~] 13 Python examples built. Data verified for IMU, camera, battery, device info, config, parameters, flashlight and audio. Environment and steps stayed silent while the phone was still; GPS and `camera_calibrator` still to verify
- [ ] Add C++ (`ament_cmake`) versions of the examples
- [ ] Publish the workspace in a GitHub repository
- [ ] Add it to the app's documentation and website
