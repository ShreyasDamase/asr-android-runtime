# ASR Android: Robot Readiness Checklist

Key: `[ ]` not started, `[~]` in progress, `[x]` done and verified on a phone, `[?]` needs a decision

Rules: branch from `develop`. No new libraries without asking. New UI matches the current UI. Never install or update the Pixi environment. Labels use standard ROS 2 terms only.

## 0. Discovery label and docs
- [x] Status uses ROS 2 terms (`DISCOVERY`, `NO OTHER NODES`, `NODES DISCOVERED`); monitor documented as optional on USB and Wi-Fi
- [x] Screen switches update when the DDS path changes without the UI
- [~] `ROS2_ENABLE` now keeps the selected DDS paths instead of forcing Wi-Fi. Code built, test pending

## 1. Correct sensor data
- [x] Timestamps use Unix time on all ROS 2 sensor messages (IMU, camera, GPS, illuminance and battery verified on phone)
- [x] IMU orientation falls back to Game Rotation Vector; verified with the phone moving
- [x] IMU covariance no longer marks gyro, accel and valid orientation as "no data"
- [ ] Check IMU publish rate (about 520 Hz measured) and whether it needs a limit
- [x] `camera_info` uses phone-reported lens values; `K[0]=0` (uncalibrated) when the phone has none. Verified: `fx=474`, center `320,240` at 640x480. Device values, not a checkerboard calibration
- [x] Separate `frame_id` per sensor, prefixed with the node namespace
- [~] `tf_static`: approved. Needs `tf2_msgs` cross-built for Android

## 2. Configuration
- [~] Phone UI: `ROS_DOMAIN_ID` and node name, locked behind EDIT. Domains 0, 5, 42, 101, 232 and renaming tested through the service; tapping APPLY still needs a manual test
- [x] Phone UI: `ROS_STATIC_PEERS`, locked behind EDIT. Verified on Wi-Fi where the router blocks multicast; the Mac may also need `ROS_STATIC_PEERS=<phone IP>`
- [x] Remote config over `config/set` and `config/state` topics, verified on phone. `ros2 param` to the phone doesn't work (Fast DDS client got no reply from a Cyclone service), so a Python node on the Mac offers real parameters and forwards them; verified
- [~] Sensor on/off and IMU rate limit commands accepted and saved. Effect of each sensor toggle and survival across restarts still to verify
- [x] Camera resolution presets keep 640x480 as default, with live FPS in `config/state`. USB, back main camera: 320x240 29.7 FPS 0.4 MB/s; 640x480 29.8 FPS 1.5 MB/s; 1280x960 29.7 FPS 5.2 MB/s; 1920x1440 20.2 FPS 9.6 MB/s. Wi-Fi 640x480 29.9 FPS
- [x] Actual resolution shown in `config/state` (1280x720 requested gives 1280x960 on this phone)
- [~] Refresh the `ROS_DOMAIN_ID` and node name fields when changed from outside the screen. Code done, not verified
- [~] Checkerboard calibration override: `camera.calibration` key, saved per camera, plus Python `camera_calibrator`. Code built; needs a real checkerboard to verify

## 3. Multiple cameras
- [x] Cameras listed in `config/state`, selected by ID, with `auto`. First phone exposes only `0` and `1` to apps; hidden IDs are rejected
- [x] Switch cameras remotely while running; front camera verified
- [~] `camera.concurrent` reports supported pairs from the phone. Both test phones report none, so two streams can't be verified on this hardware
- [?] Topic naming for extra camera streams

## 4. Robot-mounted operation
- [?] Start on boot (check Android limits on camera and mic services)
- [x] Locked and dozing phone keeps publishing: 29.9 FPS and 417 Hz over Wi-Fi, verified. Wake and Wi-Fi locks now held whenever the runtime or DDS is active
- [ ] Recovery after a link drop
- [x] Thermal status and battery percent in `config/state`, verified
- [ ] 8-hour run

## 5. Security
- [x] `AsrService` is not exported in release builds; debug keeps adb. Verified in the merged release and debug manifests
- [ ] Verify the control receiver on a signed release build
- [x] Other exported components reviewed: only the launcher and `MainActivity` are exported, and neither reads intent extras (code read only)
- [x] GPS coordinates and spoken text removed from logs (code changed and built)
- [ ] Fix the `stop_asr` launcher shortcut, which targets a service and likely never worked
- [~] REMOTE CONFIG switch, default off. Rejection of `config/set` verified; on-screen switch and flashlight/speaker gating not verified yet
- [x] ROS 2 command contents are no longer written to the log
- [~] SROS2: approved. Mac is ready; phone needs OpenSSL and `dds_security` plugins cross-built
- [x] TLS for TCP to ASR Studio: dropped by decision. TCP stays unchanged; this work targets ROS 2
- [ ] Update privacy policy and Data Safety only after these ship and are tested

## 6. Stability and memory (last, across all features above)
- [ ] 1-hour soak test with memory and thread counts (no leak proven yet)
- [~] IMU and camera buffers reused instead of allocated per sample. Installed on the phone; effect not measured yet
- [~] Fixed a shutdown deadlock risk in native code and cleared the service listener on destroy. Built and running; start/stop cycles still to test
- [x] TCP threads reviewed: disconnect interrupts and joins them (code read only)

## Future: examples workspace for students

Location: `~/Terminator/ROS2_PROJECTS/asr_android_ws` (package `asr_android_examples`)

- [~] 13 Python examples built. Config and parameter nodes, flashlight and audio verified; the rest still need a data check
- [ ] Add C++ (`ament_cmake`) versions of the examples
- [ ] Publish the workspace in a GitHub repository
- [ ] Add it to the app's documentation and website
