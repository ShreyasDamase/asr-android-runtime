# ASR Android ROS 2 Project Roadmap Checklist

This checklist records the original ROS 2 implementation roadmap. Its historical verification notes predate the Cyclone DDS backend switch; see [`android-dds-backend-comparison.md`](android-dds-backend-comparison.md) and [`ASR_ANDROID_TRANSPORT_MILESTONES.md`](ASR_ANDROID_TRANSPORT_MILESTONES.md) for current backend and transport status.

## PHASE 1 — ROS 2 COMMUNICATION CORE

- [x] 1. ROS 2 subscriber / inbound communication
    - Real rcl_subscription_t
    - Start with std_msgs/msg/String
    - ROS 2 → DDS → Android → native callback → Kotlin
    - Verify Android appears as a ROS 2 subscriber
- [x] 2. Prove complete two-way ROS 2 communication
    - Android → ROS 2 publishing works
    - ROS 2 → Android subscription works
    - Verified on macOS workstation

## PHASE 2 — EXISTING ANDROID SENSOR VALIDATION

- [x] 3. GPS validation/fix
    - Retest with Android Location actually enabled
    - Fixed RuntimeException (MainLooper required for location updates)
    - Verified NavSatFix output on workstation
- [x] 4. Validate every currently implemented ROS sensor
    - [x] IMU (sensor_msgs/msg/Imu) - High rate (100Hz+) verified
    - [x] GPS (sensor_msgs/msg/NavSatFix) - Verified with real hardware
    - [x] Battery (sensor_msgs/msg/BatteryState) - Added mandatory fields (present, health, tech)
    - [x] Illuminance (sensor_msgs/msg/Illuminance) - Verified
    - [x] Pressure (sensor_msgs/msg/FluidPressure) - Handled (hardware dependent)
    - [x] Camera compressed image (sensor_msgs/msg/CompressedImage)
    - [x] CameraInfo (sensor_msgs/msg/CameraInfo)
    - [x] Status (std_msgs/msg/String) - Heartbeat verified

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
    - [x] Capability Exposure: Added `/asr_android/capabilities` topic publishing hardware JSON on startup.

## PHASE 5 — MULTI-DEVICE IDENTITY & DISCOVERY STABILITY

- [x] 8.5 Discovery Stability (Urgent Fixes)
    - [x] Added `MulticastLock` to prevent Android from dropping discovery packets.
    - [x] Added `onTaskRemoved` cleanup for clean ROS 2 node shutdown on app swipe.
    - [x] Forced `ROS_NOSHM=1` and `ROS_LOCALHOST_ONLY=0` for Wi-Fi reliability.
    - [x] Implemented dynamic session suffix to prevent discovery collisions.
- [x] 9. Persistent automatic ROS device identity
    - Reuse existing persistent ASR device ID
- [x] 10. ROS namespace isolation
    - Topics now isolated under `/asr_android_<persistent-device-id>/`
- [x] 11. Friendly device/camera naming
    - Alias-based naming support added via `ACTION_SET_ALIAS` intent.

## PHASE 6 — RELIABILITY AND PERFORMANCE

- [x] 12. Review ROS QoS
    - Implemented `Best Effort` for high-rate sensors (IMU, GPS, Camera)
    - Implemented `Reliable` + `Transient Local` for Status and Capabilities.
- [x] 13. Repeated Wi-Fi failure/recovery testing
    - Verified stable discovery on Domain 0 with MulticastLock and clean session naming.
- [x] 14. ROS 2 + existing TCP simultaneously
    - Verified both transports operational at the same time.

## PHASE 7 — ASR STUDIO ROS SUPPORT

- [ ] 15. ROS inspection in ASR Studio
- [ ] 16. ASR Studio ROS tab/UI

## PHASE 8 — ARCHITECTURE AND DOCUMENTATION

- [ ] 17. Freeze production architecture
- [ ] 18. Full documentation

## PHASE 9 — RELEASE HARDENING

- [ ] 19. Android release cleanup (R8/ProGuard, symbol stripping)
- [ ] 20. Play Store readiness

## PHASE 10 — LONG-TERM VALIDATION

- [ ] 21. Long regression testing
- [ ] 22. Multi-device compatibility

## PHASE 11 — FINAL RELEASE

- [ ] 23. Final bug fixing
- [ ] 24. Final performance audit
- [ ] 25. Documentation freeze
- [ ] 26. Final branch/PR cleanup
- [ ] 27. Final release
