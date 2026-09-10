# ASR Android ROS 2 Project Roadmap Checklist

This checklist tracks the implementation status of ROS 2 features in the ASR Android app.

## PHASE 1 — ROS 2 COMMUNICATION CORE

- [x] 1. ROS 2 subscriber / inbound communication
    - Real rcl_subscription_t
    - Start with std_msgs/msg/String
    - ROS 2 → DDS → Android → native callback → Kotlin
    - Verify Android appears as a ROS 2 subscriber
- [x] 2. Prove complete two-way ROS 2 communication
    - Android → ROS 2 publishing already works
    - ROS 2 → Android subscription must work
    - Confirm both directions independently

## PHASE 2 — EXISTING ANDROID SENSOR VALIDATION

- [x] 3. GPS validation/fix
    - Retest with Android Location actually enabled
    - Fixed RuntimeException (MainLooper required for location updates)
    - Verified NavSatFix output on workstation
- [x] 4. Validate every currently implemented ROS sensor
    - [x] IMU (sensor_msgs/msg/Imu)
    - [x] GPS (sensor_msgs/msg/NavSatFix)
    - [/] Battery (sensor_msgs/msg/BatteryState) - Topic confirmed, data event-driven
    - [x] Illuminance (sensor_msgs/msg/Illuminance)
    - [x] Pressure (sensor_msgs/msg/FluidPressure) - Confirmed absent on test hardware
    - [ ] Temperature - Hardware absent
    - [ ] Relative humidity - Hardware absent
    - [x] Camera compressed image (sensor_msgs/msg/CompressedImage)
    - [x] CameraInfo (sensor_msgs/msg/CameraInfo)
    - Verify discovery, type, actual data, expected behavior/rate, lifecycle

## PHASE 3 — CAMERA COMPLETION

- [x] 5. Fix camera orientation
    - Current ROS camera image is rotated 90 degrees
    - Applied `setTargetRotation(ROTATION_90)` to `ImageAnalysis` to match sensor native orientation.
- [x] 6. Refine CameraInfo
    - Correctly correspond to camera, resolution and orientation
    - Initialized K, R, P matrices and zeroed out uninitialized ROI/binning fields.
- [x] 7. Camera performance validation
    - Verified FPS and pipeline latency (observed ~10-15ms processing time).

## PHASE 4 — SENSOR/RUNTIME CONFIGURATION

- [x] 8. Central sensor/capability configuration
    - Hardware capability detection (via CapabilityManager)
    - ASR module enablement (via ProfileManager/AsrService)
    - ROS exposure enablement (via Ros2Runtime toggle)
    - [NEW] Capability Exposure: Added `/asr_android/capabilities` topic publishing hardware JSON on startup.

## PHASE 5 — MULTI-DEVICE IDENTITY

- [ ] 9. Persistent automatic ROS device identity
    - Reuse existing persistent ASR device ID
- [ ] 10. ROS namespace isolation
    - `/asr_android/<persistent-device-id>/...`
- [ ] 11. Friendly device/camera naming
    - Alias-based naming

... (rest of the file remains same)
