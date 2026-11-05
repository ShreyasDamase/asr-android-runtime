# ASR Android: Robot Readiness Checklist

Key: `[ ]` not started, `[~]` in progress, `[x]` done and verified on a phone, `[?]` needs a decision

Rules: branch from `develop`. No new libraries without asking. New UI matches the current UI. Never install or update the Pixi environment. Labels use standard ROS 2 terms only.

## 0. Discovery label and docs
- [x] Status uses ROS 2 terms (`DISCOVERY`, `NO OTHER NODES`, `NODES DISCOVERED`); monitor documented as optional on USB and Wi-Fi
- [x] Screen switches update when the DDS path changes without the UI
- [ ] Retire or fix the old `ROS2_ENABLE` action, which always selects Wi-Fi

## 1. Correct sensor data
- [x] Timestamps use Unix time on all ROS 2 sensor messages (IMU, camera, GPS, illuminance and battery verified on phone)
- [x] IMU orientation falls back to Game Rotation Vector; verified with the phone moving
- [x] IMU covariance no longer marks gyro, accel and valid orientation as "no data"
- [ ] Check IMU publish rate (about 520 Hz measured) and whether it needs a limit
- [x] `camera_info` uses phone-reported lens values; `K[0]=0` (uncalibrated) when the phone has none. Verified: `fx=474`, center `320,240` at 640x480. Device values, not a checkerboard calibration
- [x] Separate `frame_id` per sensor, prefixed with the node namespace
- [?] `tf_static`: `tf2_msgs` is not in the Android prefix, so this needs a new native package (needs permission)

## 2. Configuration
- [~] Phone UI: `ROS_DOMAIN_ID` and node name added; service path tested across domains from the Mac; tap test of APPLY pending
- [ ] Phone UI: peer IP list
- [ ] Remote config from the Mac with real ROS 2 parameters (`ros2 param`, `rqt_reconfigure`, Foxglove)
- [ ] Sensor on/off, rates, camera settings saved across restarts
- [ ] Camera resolution presets keep 640x480 as the default and show measured FPS for each. USB, back main camera: 320x240 29.7 FPS 0.4 MB/s; 640x480 29.8 FPS 1.5 MB/s; 1280x960 29.7 FPS 5.2 MB/s; 1920x1440 20.2 FPS 9.6 MB/s. Wi-Fi not measured
- [ ] Show the resolution actually used (1280x720 requested gives 1280x960 on this phone)
- [ ] Refresh the `ROS_DOMAIN_ID` and node name fields when changed remotely
- [ ] Optional checkerboard calibration override for `camera_info` (standard `camera_calibration` workflow)

## 3. Multiple cameras
- [ ] List cameras in `capabilities`; select one by ID (this phone: back 0, 21; front 1, 101)
- [ ] Switch cameras remotely while running
- [ ] Two streams only on phones that report support (this phone doesn't list it; test it)
- [?] Topic naming for extra camera streams

## 4. Robot-mounted operation
- [?] Start on boot (check Android limits on camera and mic services)
- [ ] Remote camera start with screen off; recovery after link drops
- [ ] Thermal and battery warnings; 8-hour run

## 5. Security
- [ ] Lock down exported `AsrService` in release builds; review other exported components
- [ ] "Allow remote control" switch; camera and mic off remotely by default
- [?] SROS2: Mac is ready; phone needs OpenSSL and `dds_security` plugins (new native libs, needs permission)
- [?] TLS for TCP to ASR Studio
- [ ] Update privacy policy and Data Safety only after these ship and are tested

## 6. Stability and memory (last, across all features above)
- [ ] 1-hour soak test with memory and thread counts (no leak proven yet)
- [ ] Check per-sample IMU and per-frame camera allocations
- [ ] Check start/stop cycles, native messages, TCP threads and listeners
