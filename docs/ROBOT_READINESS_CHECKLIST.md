# ASR Android: Robot Readiness Checklist

Key: `[ ]` not started, `[~]` in progress, `[x]` done and verified on a phone, `[?]` needs a decision

Rules: branch from `develop`. No new libraries without asking. New UI matches the current UI. Never install or update the Pixi environment. Labels use standard ROS 2 terms only.

## 0. Discovery label and docs
- [x] Status uses ROS 2 terms (`DISCOVERY`, `NO OTHER NODES`, `NODES DISCOVERED`); monitor documented as optional on USB and Wi-Fi
- [ ] Screen switches must update when the DDS path changes without the UI (seen when `ROS2_ENABLE` switched to Wi-Fi)
- [ ] Retire or fix the old `ROS2_ENABLE` action, which always selects Wi-Fi

## 1. Correct sensor data
- [x] Timestamps use Unix time on all ROS 2 sensor messages (IMU and camera verified on phone; GPS, battery and environment use the same code, not yet checked)
- [x] IMU orientation falls back to Game Rotation Vector; real values verified on phone
- [x] IMU covariance no longer marks gyro, accel and valid orientation as "no data"
- [ ] Check IMU publish rate (about 520 Hz measured) and whether it needs a limit
- [ ] `camera_info` uses real lens values, not `fx = width * 0.8`
- [ ] Separate `frame_id` per sensor, and `tf_static`

## 2. Stability and memory
- [ ] 1-hour soak test with memory and thread counts (no leak proven yet)
- [ ] Check per-sample IMU and per-frame camera allocations
- [ ] Check start/stop cycles, native messages, TCP threads and listeners

## 3. Configuration
- [ ] Phone UI: domain ID, node name, peer IP list
- [ ] Remote config from the Mac with real ROS 2 parameters (`ros2 param`, `rqt_reconfigure`, Foxglove)
- [ ] Sensor on/off, rates, camera settings saved across restarts

## 4. Multiple cameras
- [ ] List cameras in `capabilities`; select one by ID (this phone: back 0, 21; front 1, 101)
- [ ] Switch cameras remotely while running
- [ ] Two streams only on phones that report support (this phone doesn't list it; test it)
- [?] Topic naming for extra camera streams

## 5. Robot-mounted operation
- [?] Start on boot (check Android limits on camera and mic services)
- [ ] Remote camera start with screen off; recovery after link drops
- [ ] Thermal and battery warnings; 8-hour run

## 6. Security
- [ ] Lock down exported `AsrService` in release builds; review other exported components
- [ ] "Allow remote control" switch; camera and mic off remotely by default
- [?] SROS2: Mac is ready; phone needs OpenSSL and `dds_security` plugins (new native libs, needs permission)
- [?] TLS for TCP to ASR Studio
- [ ] Update privacy policy and Data Safety only after these ship and are tested
