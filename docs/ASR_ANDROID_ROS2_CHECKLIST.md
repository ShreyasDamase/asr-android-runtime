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

- [ ] 3. GPS validation/fix
    - Retest with Android Location actually enabled
    - Do not change code unless the real test demonstrates a software problem
- [ ] 4. Validate every currently implemented ROS sensor
    - IMU
    - GPS
    - Battery
    - Illuminance
    - Pressure
    - Temperature
    - Relative humidity, only where hardware actually provides it
    - Camera compressed image
    - CameraInfo
    - Verify discovery, type, actual data, expected behavior/rate, lifecycle

## PHASE 3 — CAMERA COMPLETION

- [ ] 5. Fix camera orientation
    - Current ROS camera image is rotated 90 degrees
    - Fix at the appropriate Android camera pipeline/orientation boundary
- [ ] 6. Refine CameraInfo
    - Correctly correspond to camera, resolution and orientation
- [ ] 7. Camera performance validation
    - FPS, jitter, latency, CPU, memory, Wi-Fi behavior

## PHASE 4 — SENSOR/RUNTIME CONFIGURATION

- [ ] 8. Central sensor/capability configuration
    - Hardware capability detection
    - ASR module enablement
    - ROS exposure enablement

## PHASE 5 — MULTI-DEVICE IDENTITY

- [ ] 9. Persistent automatic ROS device identity
    - Reuse existing persistent ASR device ID
- [ ] 10. ROS namespace isolation
    - `/asr_android/<persistent-device-id>/...`
- [ ] 11. Friendly device/camera naming
    - Alias-based naming

## PHASE 6 — RELIABILITY AND PERFORMANCE

- [ ] 12. Review ROS QoS
- [ ] 13. Repeated Wi-Fi failure/recovery testing
- [ ] 14. ROS 2 + existing TCP simultaneously

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
