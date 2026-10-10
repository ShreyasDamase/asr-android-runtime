# ASR Android Transport and UI Milestones

This document began as a branch plan. The current status below reflects the work on `develop` and its release branch `main`; remaining platform checks are called out explicitly.

## Lifecycle boundaries

- `AsrService` coordinates lifecycles; it is not a reason for one mode to start another.
- Sensor runtime owns camera, Android sensor/audio/GPS modules, its heartbeat, and sensor-related wake/Wi-Fi locks.
- TCP owns the ASR Studio socket and reconnect lifecycle. Starting or stopping sensors does not connect or disconnect TCP.
- ROS 2 owns its native DDS runtime and multicast lock. DDS discovery can run with sensors stopped and TCP disabled; sensor data is published when sensors are active.
- Runtime, TCP, and ROS 2 state/preferences remain separate. Only Exit shuts all three down together.
- Transport module commands route through the TCP lifecycle control so they cannot bypass its switch or cleanup path.

## Milestone 1 — Commissioning UI and app exit

- Keep the UI in XML and use the existing Android and Material components.
- Present sensor runtime, TCP for ASR Studio, and ROS 2 DDS as separate sections.
- Runtime start and stop manage sensors only. TCP and ROS 2 each have independent switches and lifecycles.
- Keep secondary device and diagnostic information available without crowding the main screen.
- Add a confirmed Exit action that stops sensor modules, TCP, ROS 2, and the foreground service before closing the task.
- Add no dependencies.

## Milestone 2 — Lifecycle hardening and module boundaries

- **Status: Complete (2026-10-10).**
- Exercise TCP only, ROS 2 only, both together, and neither while retaining one set of sensor managers.
- Audit start, stop, restart, sticky service recovery, pending camera initialization, locks, timers, and shutdown ordering.
- Make runtime state and diagnostics describe the sensor runtime and each transport accurately.
- Persist each mode independently and ensure a change in one mode cannot start or stop another.

### Milestone 2 verification

- `:app:assembleDebug` succeeds with no new dependencies.
- Installed and exercised on the connected Android phone: runtime-only does not enable TCP or DDS; TCP remains connected through runtime start and stop; DDS-only discovers ROS 2 participants with sensors stopped and TCP disabled; TCP and DDS can be enabled together.
- TCP was verified through the existing ADB reverse test path with a local listener. ROS 2 discovery was verified against the existing Pixi ROS 2 environment (`ros2 node list` showed the Android node alongside `foxglove_bridge` and `talker`).
- Tested the TCP peer-close/reconnect path; retries remain visible as disconnected/connecting while modes stay independent.

## Milestone 3 — USB-only DDS discovery

**Status: Implemented; Mac validation passed, Ubuntu and Raspberry Pi validation pending.**

- First validate that the target Android device and robot can establish an IP network link over USB.
- Configure the selected Android DDS backend (currently Cyclone DDS) to use the USB network interface without Wi-Fi DDS discovery when USB-only mode is selected.
- Expose the selected path and link/discovery status in the UI and measure discovery time.
- Do not treat ADB forwarding by itself as a USB DDS network link.

## Milestone 4 — Wi-Fi discovery and end-to-end validation

**Status: Implemented and observed on Mac; Ubuntu and Raspberry Pi validation pending.**

- Investigate and reduce Wi-Fi DDS discovery time based on measurements.
- Test TCP and ROS 2 alone and together, USB-only and Wi-Fi-only discovery, and app/service shutdown.
- Validate against the user's existing Pixi-provided ROS 2 environment without installing libraries into or resetting its configuration.

No new library may be added without the user's approval.

## Current release validation

- Cyclone DDS USB and Wi-Fi discovery were observed on Mac in under 10 seconds, with sensor messages received on both paths.
- The USB helper is optional for normal DDS operation and is only intended for persistent USB host diagnostics; it is not needed for Wi-Fi.
- Ubuntu live validation and Raspberry Pi validation have not yet been completed.
- See [`android-dds-backend-comparison.md`](android-dds-backend-comparison.md) for the recorded backend observations and their limits.
