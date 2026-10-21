# ASR Android 16 KB Page-Size Research Export

**Prepared:** 2026-10-21
**Repository:** ASR Android Runtime
**Working branch:** `bugfix/android-16kb-page-size`
**Research snapshot HEAD:** `eb775f2b5b4df63e1afc0d1605f36e5ce67bfb52`
**Status:** Cyclone artifacts now pass static 16 KB ELF and packaging checks; runtime validation on a 16 KB device is still required.

> This export consolidates the available conversation decisions, source-provenance research, APK audits, diagnostic build results, and continuation guidance. The chat history had already been compacted before this export request, so this is a technical and decision record, not a byte-for-byte raw transcript of every earlier chat turn or tool response.

## 1. Executive summary

The original baseline APKs failed because bundled native ELF libraries had LOAD segments aligned to 4 KB (`0x1000`), not because of APK ZIP alignment alone. That historical result describes the baseline artifacts below, not the current Cyclone build.

A build-tools-only experiment rebuilt the app's `libasr_ros2.so` with NDK `28.2.13676358`. That library's LOAD segments use `0x4000` (16 KB) alignment. However, the build reused the prebuilt ROS 2 and DDS libraries, so the resulting APKs still contain:

| Variant | Native `.so` files | ELF-misaligned libraries | APK ZIP alignment |
|---|---:|---:|---|
| Cyclone debug | 87 | **83** | Pass |
| Cyclone release | 87 | **83** | Pass |
| Fast DDS debug | 83 | **81** | Pass |

Changing NDK alone does not relink prebuilt `.so` files. AGP and Gradle were not implicated by this historical experiment and were deliberately left unchanged. Those baseline artifacts were not runtime-validated in a 16 KB environment.

### Current Cyclone recheck — 2026-10-21

After the native library updates in the current working tree, Cyclone debug APK, Cyclone release APK, and Cyclone release AAB were rebuilt and audited:

| Artifact | ARM64 `.so` files | ELF alignment failures | Packaging alignment |
|---|---:|---:|---|
| Cyclone debug APK | 65 | 0 | `zipalign -c -P 16 -v 4`: pass |
| Cyclone release APK | 65 | 0 | `zipalign -c -P 16 -v 4`: pass |
| Cyclone release AAB | 65 | 0 | `PAGE_ALIGNMENT_16K` |

The generated Cyclone artifacts pass static alignment checks. They have not yet been run on a 16 KB Android device, and Cyclone runtime/regression behavior remains to be validated there. Fast DDS is outside this recheck.

## 2. Conversation and authorization record

This section records the material requests and decisions relevant to the 16 KB work:

1. The user supplied an Android guidance note explaining that the warning concerns ELF LOAD alignment and native-library packaging; it stated that upgrading Gradle/NDK alone cannot fix incompatible prebuilt ROS 2 libraries. The user requested research, an explanation of the cause, and a plan before implementation.
2. The user authorized a local branch from `develop` for this work. The branch `bugfix/android-16kb-page-size` was created from `develop` at `eb775f2b5b4df63e1afc0d1605f36e5ce67bfb52`.
3. The user required both DDS backends, existing sensor behavior, TCP telemetry, ADB behavior, supported Android API levels, original APKs, and the original ROS 2 prefix to be protected. Exact native source provenance was required before dependency rebuilds; absent provenance, stop and report rather than build arbitrary/current sources.
4. The user authorized read-only research of official ROS 2, Fast DDS, Fast-CDR, and Cyclone DDS sources to propose a reproducible dependency baseline. This did not authorize native rebuilding, binary replacement, or application changes.
5. The user then authorized a build-tools-only experiment with these limits:
   - Do not change Kotlin/C++ app sources, DDS configuration, ROS 2 dependencies, or the original prefix.
   - Do not overwrite original baseline APKs or change protected branches.
   - Use an isolated temporary checkout/build and the installed NDK `28.2.13676358`.
   - Keep AGP `9.2.1` and Gradle `9.4.1`.
   - Build Cyclone debug, Cyclone release, and Fast DDS debug; inspect every packaged `.so`, compare alignment/hashes, and verify ZIP alignment.
   - Stop if prebuilt dependencies remain misaligned.
6. The experiment was run and stopped at the required point. It did not rebuild or replace any ROS 2/DDS library.
7. The user requested a detailed handoff and then asked to export the full 16 KB conversation findings and research. This document is that consolidated export.

## 3. Repository and protection status

- Original checkout: `/Users/shreyasdamase/Vanguard/ASR/asrandroid`
- Branch: `bugfix/android-16kb-page-size`
- HEAD: `eb775f2b5b4df63e1afc0d1605f36e5ce67bfb52`
- At the end of the diagnostic, the original tracked worktree was clean.
- `develop` and `main` were at the same commit as the bug-fix branch at the time of the branch check. No protected branch was changed.
- The original ignored prefix at `ros2_android_install/` was only consumed as a CMake prefix; it was not modified.
- Baseline APKs were not overwritten. Their SHA-256 values are recorded below.
- No build, installation, or runtime test was performed on an actual 16 KB Android environment.

## 4. Android build and alignment background

The official Android guidance is [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes). It calls for checking native ELF load-segment alignment and APK/AAB native-library packaging. NDK r28 and newer use 16 KB ELF alignment by default for native code that is actually built with that toolchain. That does not transform already-built prebuilt libraries.

Observed app/build configuration:

| Setting | Value |
|---|---|
| Android Gradle Plugin | `9.2.1` |
| Gradle wrapper | `9.4.1` |
| Installed and explicitly selected NDK | `28.2.13676358` (r28c) |
| ABI | `arm64-v8a` |
| `compileSdk` / `targetSdk` | 36 / 36 |
| Fast DDS `minSdk` | 26 |
| Cyclone `minSdk` | 28 |

The existing native build scripts default to NDK `27.1.12297006`, while the app's CMake caches and the experiment used `28.2.13676358`. The experiment did not change that source configuration; it selected the installed NDK through environment variables for the isolated build.

Two distinct checks matter:

- **ELF LOAD alignment:** Inspect each packaged `.so` and verify that every loadable segment meets the 16 KB requirement.
- **APK ZIP alignment:** `zipalign -c -P 16 -v 4 <apk>` checks APK packaging. It cannot make an ELF with 4 KB LOAD alignment compatible.

The project already uses AGP `9.2.1`, above Android's cited AGP packaging recommendation of 8.5.1. There was no evidence in this experiment that AGP or Gradle caused the ELF problem, so neither was upgraded.

## 5. Initial APK audit and baseline hashes

Before the isolated experiment, the original APKs were audited:

| Baseline APK | Native libraries | ELF LOAD alignment below 16 KB | ZIP alignment | SHA-256 |
|---|---:|---:|---|---|
| Fast DDS debug | 83 | 81 | Pass | `edef03864b1c7d8768f00e0365600abaa8b00b940bcdf007b66b7e95361329eb` |
| Cyclone debug | 87 | 83 | Pass | `0ac1baeb7a443101f8d389c7c8e2ab6ab8c5bd52371beddb7aef895275712615` |
| Cyclone release unsigned | 87 | 83 | Pass | `48b8c51c02a9ebe56f3f7b5f63926ab74ef4cef937ecf857fb5231d3aeefbcfe` |

The attached/connected phone reported `adb shell getconf PAGE_SIZE` = `4096`. It is a 4 KB device and cannot establish 16 KB runtime compatibility. No configured 16 KB AVD was found during the original audit.

## 6. Upstream source-provenance research

### 6.1 What was and was not recoverable

The installed prefix contains package version metadata, but no complete lockfile tying installed libraries to exact source revisions, local patches, compiler flags, or reproducible build logs. A debug path in `libfastcdr.so` pointed to `/Users/shreyasdamase/ros2_lyrical`; that workspace has no `src/`, `build/`, or `install/` directory. The Cyclone staging directory previously referenced by documentation under `/tmp/asr-cyclone-android` was also absent.

Consequently:

- Exact original source provenance is **not established**.
- Official upstream tags below are candidates for a reproducible baseline, not proof of the source of the existing binaries.
- Do not claim these candidates reproduce the original artifacts.
- The original source manifest/checkout or explicit user approval of a chosen new baseline is needed before rebuilding native dependencies.

### 6.2 ROS 2 snapshot

The official `ros2/ros2` tag `release-lyrical-20260807` resolves to [`8b631e62aadee50d564c7388bb24c34da9433b81`](https://github.com/ros2/ros2/commit/8b631e62aadee50d564c7388bb24c34da9433b81). Its repository manifest was generated on 2026-08-06.

The ROS Lyrical snapshot is useful as a published version set, but several pins differ from the local prefix or backend documentation. Current/latest distro metadata must not be substituted for original provenance.

### 6.3 Candidate package pins and relationships

The following official tags and full upstream commit SHAs were resolved during research. They are proposed candidates only.

| Repository | Candidate version/tag | Full commit SHA | Relevant relationship |
|---|---|---|---|
| [eProsima/Fast-CDR](https://github.com/eProsima/Fast-CDR) | `v2.3.6` | [`d7219391608ae8cd7971e761206dc020098bacb3`](https://github.com/eProsima/Fast-CDR/commit/d7219391608ae8cd7971e761206dc020098bacb3) | Serialization dependency of Fast DDS and Fast RTPS typesupport. |
| [eProsima/Fast-DDS](https://github.com/eProsima/Fast-DDS) | `v3.6.2` | [`39303846fb8534ef69fa65f9fa4bcc9e6a7c995a`](https://github.com/eProsima/Fast-DDS/commit/39303846fb8534ef69fa65f9fa4bcc9e6a7c995a) | Fast DDS backend; uses Fast-CDR and memory vendor. |
| [eProsima/foonathan_memory_vendor](https://github.com/eProsima/foonathan_memory_vendor) | `v1.4.1` | [`347cb67581e51273a612780eb256a3c134c10bae`](https://github.com/eProsima/foonathan_memory_vendor/commit/347cb67581e51273a612780eb256a3c134c10bae) | Fast DDS memory dependency. |
| [eclipse-cyclonedds/cyclonedds](https://github.com/eclipse-cyclonedds/cyclonedds) | `11.0.1` | [`e54e991f75a3e67f8e628da3171122e36ea5b872`](https://github.com/eclipse-cyclonedds/cyclonedds/commit/e54e991f75a3e67f8e628da3171122e36ea5b872) | Cyclone backend core. |
| [eclipse-iceoryx/iceoryx](https://github.com/eclipse-iceoryx/iceoryx) | exact snapshot commit | [`35311bfe5338392d65b18f148de70b803f6fc48e`](https://github.com/eclipse-iceoryx/iceoryx/commit/35311bfe5338392d65b18f148de70b803f6fc48e) | Commit pinned by ROS snapshot; include only as required by selected Cyclone build options. |
| [ros2/rmw_cyclonedds](https://github.com/ros2/rmw_cyclonedds) | `4.1.5` | [`74e8047eb717953b31155323bfe137e81caa423e`](https://github.com/ros2/rmw_cyclonedds/commit/74e8047eb717953b31155323bfe137e81caa423e) | Cyclone implementation of RMW; depends on Cyclone DDS and shared ROS middleware/typesupport. |
| [ros2/rmw_fastrtps](https://github.com/ros2/rmw_fastrtps) | `9.4.9` | [`636a1080b24ed7bbd46267a9d0fc6660e0e205ba`](https://github.com/ros2/rmw_fastrtps/commit/636a1080b24ed7bbd46267a9d0fc6660e0e205ba) | Fast DDS ROS middleware implementation; depends on Fast DDS/Fast-CDR and Fast RTPS typesupport. |
| [ros2/rmw](https://github.com/ros2/rmw) | `7.10.2` | [`2fd339ba950dc70c2f594e0c3fd69d8a1b217d63`](https://github.com/ros2/rmw/commit/2fd339ba950dc70c2f594e0c3fd69d8a1b217d63) | Shared middleware interface for both RMW implementations. |
| [ros2/rmw_dds_common](https://github.com/ros2/rmw_dds_common) | `6.0.0` | [`2f00e6e9208e925d61330ea92f857b2e67d595ab`](https://github.com/ros2/rmw_dds_common/commit/2f00e6e9208e925d61330ea92f857b2e67d595ab) | Shared DDS discovery and common functionality. |
| [ros2/rcutils](https://github.com/ros2/rcutils) | `7.1.3` | [`71406a100612c0c2bd6c0c1a8babe26e765d1499`](https://github.com/ros2/rcutils/commit/71406a100612c0c2bd6c0c1a8babe26e765d1499) | ROS utility layer used by `rcl` and middleware. |
| [ros2/rcl](https://github.com/ros2/rcl) | `10.4.4` | [`da0b5e70247ef1fb0425bf35417d4e9fdf11efb9`](https://github.com/ros2/rcl/commit/da0b5e70247ef1fb0425bf35417d4e9fdf11efb9) | ROS client library linked by the Android native bridge. |
| [ros2/rcl_interfaces](https://github.com/ros2/rcl_interfaces) | `2.4.5` | [`b90e36e4adf5ed878efa2f365a84798f782618cd`](https://github.com/ros2/rcl_interfaces/commit/b90e36e4adf5ed878efa2f365a84798f782618cd) | Parameter, service, and type-description message interfaces. |
| [ros2/common_interfaces](https://github.com/ros2/common_interfaces) | `5.9.3` | [`6099c3b82abe20da50bf410e8173127ea1d83d30`](https://github.com/ros2/common_interfaces/commit/6099c3b82abe20da50bf410e8173127ea1d83d30) | Standard, geometry, and sensor messages used by ASR. |
| [ros2/rosidl](https://github.com/ros2/rosidl) | `5.2.1` | [`024f0b9636d716b9756c25ade0b6d7b15bde18e9`](https://github.com/ros2/rosidl/commit/024f0b9636d716b9756c25ade0b6d7b15bde18e9) | ROS interface generation/runtime framework. |
| [ros2/rosidl_core](https://github.com/ros2/rosidl_core) | `0.4.3` | [`2e34a7cb22db268e2bfac06eb8b47615818051ad`](https://github.com/ros2/rosidl_core/commit/2e34a7cb22db268e2bfac06eb8b47615818051ad) | Core ROSIDL generator/runtime definitions. |
| [ros2/rosidl_typesupport](https://github.com/ros2/rosidl_typesupport) | `3.4.2` | [`f21f4d6cbb76e0da104bd8fdd093fc6a3ade59d2`](https://github.com/ros2/rosidl_typesupport/commit/f21f4d6cbb76e0da104bd8fdd093fc6a3ade59d2) | Common C/C++ typesupport interfaces. |
| [ros2/rosidl_typesupport_fastrtps](https://github.com/ros2/rosidl_typesupport_fastrtps) | `3.9.6` | [`a92a44e13706e6716e9b5d62be3983c973d97c0e`](https://github.com/ros2/rosidl_typesupport_fastrtps/commit/a92a44e13706e6716e9b5d62be3983c973d97c0e) | Fast DDS C/C++ message serialization/typesupport. |
| [ros2/rosidl_dynamic_typesupport](https://github.com/ros2/rosidl_dynamic_typesupport) | `0.4.1` | [`f9ee0542b725244ba5633cb3c03d2f9cc0da0064`](https://github.com/ros2/rosidl_dynamic_typesupport/commit/f9ee0542b725244ba5633cb3c03d2f9cc0da0064) | Dynamic typesupport framework used by Fast DDS stack. |
| [ros2/rosidl_dynamic_typesupport_fastrtps](https://github.com/ros2/rosidl_dynamic_typesupport_fastrtps) | `0.5.1` | [`de01f56ccbf80851b5460a3ca169b4ca71c58b48`](https://github.com/ros2/rosidl_dynamic_typesupport_fastrtps/commit/de01f56ccbf80851b5460a3ca169b4ca71c58b48) | Fast DDS dynamic typesupport implementation. |
| [ros2/rcpputils](https://github.com/ros2/rcpputils) | `2.14.5` | [`b8775ec03b9412789b34358c738b0e87c53e1b31`](https://github.com/ros2/rcpputils/commit/b8775ec03b9412789b34358c738b0e87c53e1b31) | Shared C++ utility dependency. |
| [ros2/rcl_logging](https://github.com/ros2/rcl_logging) | `3.4.1` | [`904c0971bd0b3581094af853b76b56304915248e`](https://github.com/ros2/rcl_logging/commit/904c0971bd0b3581094af853b76b56304915248e) | ROS client logging components. |
| [ros2/ros2_tracing](https://github.com/ros2/ros2_tracing) | `8.10.2` | [`24c3dea0fcee6f1fd0eb5244c6e1ea390dd479ac`](https://github.com/ros2/ros2_tracing/commit/24c3dea0fcee6f1fd0eb5244c6e1ea390dd479ac) | Tracing dependency recorded in ROS package metadata. |
| [ros/class_loader](https://github.com/ros/class_loader) | `2.9.4` | [`86d1053cbdfdb80d3f4fd631ea1e4b148ce25c41`](https://github.com/ros/class_loader/commit/86d1053cbdfdb80d3f4fd631ea1e4b148ce25c41) | Plugin/class loading support; present in prefix. |
| [ros/pluginlib](https://github.com/ros/pluginlib) | `5.8.5` | [`3f1889cd34a2c989cbcae141cc9e6b24471c2e37`](https://github.com/ros/pluginlib/commit/3f1889cd34a2c989cbcae141cc9e6b24471c2e37) | Plugin infrastructure; present in prefix, not directly linked by app CMake target. |

The Fast DDS [`fastdds.repos` file at v3.6.2](https://github.com/eProsima/Fast-DDS/blob/v3.6.2/fastdds.repos) declares Fast-CDR `v2.3.6`, Fast DDS `v3.6.2`, and foonathan memory vendor `v1.4.1`.

### 6.4 Version discrepancies identified

The preserved prefix contains 93 package XML version records. The relevant comparisons were:

| Component | Prefix or backend documentation | ROS Lyrical snapshot | Candidate decision/status |
|---|---:|---:|---|
| Fast-CDR | `2.3.6` | `2.3.6` | Version matches; original source build remains unproven. |
| Fast DDS | CMake reports `3.6.2.0` | `3.6.2` | Version matches, with CMake's four-component version string. |
| foonathan memory vendor | `1.4.1` | `1.4.1` | Version matches. |
| Cyclone DDS | `11.0.1` in backend documentation | `11.0.1` | Version matches; original compiler flags/source provenance missing. |
| `rmw_cyclonedds_cpp` | `4.1.5` in backend documentation | `4.1.4` | Snapshot differs; candidate follows documented/local version `4.1.5`. |
| `rmw_fastrtps_cpp` | `9.4.9` | `9.4.9` | Version matches. |
| `rmw` / RMW implementation support | prefix `7.10.2` | snapshot `7.10.1` | Snapshot differs; candidate follows prefix `7.10.2`. |
| `rmw_dds_common` | `6.0.0` | `6.0.0` | Version matches. |
| `rcutils` | `7.1.3` | `7.1.2` | Snapshot differs; candidate follows prefix `7.1.3`. |
| Fast RTPS C/C++ typesupport | prefix `3.9.6` | snapshot `3.9.5` | Snapshot differs; candidate follows prefix `3.9.6`. |
| `pluginlib` | prefix `5.8.5` | snapshot `5.8.4` | Snapshot differs; candidate follows prefix, though not directly linked by app CMake. |
| `common_interfaces` | `sensor_msgs`/`geometry_msgs` `5.9.3` | `5.9.3` | Package version matches; exact original build provenance unproven. |
| `rcl` | `10.4.4` | `10.4.4` | Package version matches. |
| `rcl_interfaces` | `2.4.5` | `2.4.5` | Package version matches. |

The ROS 2 `release-lyrical-20260807` manifest is a candidate snapshot only. It cannot serve as evidence for the exact source revisions or local modifications used in the original build.

### 6.5 Why version changes could affect functionality

These native packages form coupled dependency sets:

- **Fast DDS path:** Fast DDS + Fast-CDR + `rmw_fastrtps` + ROSIDL Fast RTPS typesupport + shared `rmw`/`rcl`/`rcutils` packages.
- **Cyclone path:** Cyclone DDS + `rmw_cyclonedds` + shared `rmw`/`rcl`/`rcutils` packages + ROSIDL introspection libraries.
- **Both paths:** common ROS interfaces/typesupport, message definitions, logging/runtime, and the JNI bridge.

Changing one or more versions may affect ABI compatibility, message serialization/typesupport, discovery, QoS interoperability, or runtime behavior. The version differences above must be resolved as a consistent source set and tested; replacing an individual `.so` based solely on matching filenames is unsafe.

The app's current CMake backend selection is in `app/src/main/cpp/CMakeLists.txt`. The Fast DDS branch links Fast DDS/Fast-CDR and Fast RTPS message typesupport. The Cyclone branch links Cyclone and generated ROSIDL introspection C typesupport. Both variants must remain available.

## 7. Build-tools-only experiment

### 7.1 Isolation and environment

- A detached worktree was created at `/tmp/asr-android-16kb-diagnostic-20261016`, at the same commit as the original branch.
- The original checkout, baseline APKs, and ROS 2 prefix were not used as build-output locations.
- Temporary Gradle project/user caches were isolated under `/tmp` and removed after the build.
- The diagnostic APKs and isolated worktree were retained under `/tmp/asr-android-16kb-diagnostic-20261016`.
- The only installed NDK was `/Users/shreyasdamase/Library/Android/sdk/ndk/28.2.13676358`; generated CMake caches independently confirmed it.
- The original prefix path was supplied to CMake: `/Users/shreyasdamase/Vanguard/ASR/asrandroid/ros2_android_install`.
- AGP remained `9.2.1`; Gradle remained `9.4.1`.

Build tasks:

```bash
./gradlew --no-daemon \
  --project-cache-dir /tmp/asr-android-16kb-diagnostic-20261016/gradle-project-cache \
  :app:assembleCycloneDebug \
  :app:assembleCycloneRelease \
  :app:assembleFastddsDebug
```

For a future rerun, set:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/28.2.13676358"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export ROS2_ANDROID_PREFIX="/Users/shreyasdamase/Vanguard/ASR/asrandroid/ros2_android_install"
```

Run Gradle from the isolated worktree and direct Gradle caches/build outputs to a separate temporary directory. Do not point `ROS2_ANDROID_PREFIX` at a writable build/install target; the existing native build scripts can install directly into their prefix and were not run in this diagnostic.

### 7.2 Diagnostic artifacts

| Variant | Isolated APK | SHA-256 |
|---|---|---|
| Cyclone debug | `/tmp/asr-android-16kb-diagnostic-20261016/app/build/outputs/apk/cyclone/debug/app-cyclone-debug.apk` | `1d3a1c9432131b21277f6c9b6d8de557337310ce2a3fb5f3eec7bd191295d0b8` |
| Cyclone release unsigned | `/tmp/asr-android-16kb-diagnostic-20261016/app/build/outputs/apk/cyclone/release/app-cyclone-release-unsigned.apk` | `39d1e58c412436fe875cb9319bd8e3599c8d67ff7ef5de19463717bf45f986cb` |
| Fast DDS debug | `/tmp/asr-android-16kb-diagnostic-20261016/app/build/outputs/apk/fastdds/debug/app-fastdds-debug.apk` | `bf59ed406aadabdac3581484d0a9cda12a99fae68b5e6f1bbf6453cbce4c23d4` |

### 7.3 What changed in the build output

- `libasr_ros2.so` was compiled for each variant. Its hash differs from the corresponding baseline APK and its ELF LOAD alignment is `0x4000`.
- All other packaged `.so` content matched the corresponding baseline APK.
- The Fast DDS app's prebuilt native dependencies were reused.
- The Cyclone app's prebuilt shared dependencies and Cyclone-specific `jniLibs` were reused.
- No DDS library or ROS 2 dependency was rebuilt.
- No application source, DDS configuration, dependency version, or original prefix file was changed.

### 7.4 Alignment and packaging verification

NDK inspection tool:

```text
$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf
```

APK ZIP alignment command:

```bash
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 <apk>
```

All three APKs reported `Verification successful` for ZIP alignment. The ELF audit found the 81 common failing libraries below. Cyclone debug and release also fail on the two Cyclone-specific libraries immediately after the list.

### 7.5 Exact misaligned-library inventory

These 81 native libraries fail the ELF LOAD alignment check in **all three** diagnostic APKs:

```text
libament_index_cpp.so
libbenchmark.so
libbenchmark_main.so
libbuiltin_interfaces__rosidl_generator_c.so
libbuiltin_interfaces__rosidl_typesupport_c.so
libbuiltin_interfaces__rosidl_typesupport_cpp.so
libbuiltin_interfaces__rosidl_typesupport_fastrtps_c.so
libbuiltin_interfaces__rosidl_typesupport_fastrtps_cpp.so
libbuiltin_interfaces__rosidl_typesupport_introspection_c.so
libbuiltin_interfaces__rosidl_typesupport_introspection_cpp.so
libclass_loader.so
libconsole_bridge.so
libfastcdr.so
libfastdds.so
libgeometry_msgs__rosidl_generator_c.so
libgeometry_msgs__rosidl_typesupport_c.so
libgeometry_msgs__rosidl_typesupport_fastrtps_c.so
libimage_processing_util_jni.so
libmemory_tools.so
libmemory_tools_interpose.so
librcl.so
librcl_interfaces__rosidl_generator_c.so
librcl_interfaces__rosidl_typesupport_c.so
librcl_interfaces__rosidl_typesupport_cpp.so
librcl_interfaces__rosidl_typesupport_fastrtps_c.so
librcl_interfaces__rosidl_typesupport_fastrtps_cpp.so
librcl_interfaces__rosidl_typesupport_introspection_c.so
librcl_interfaces__rosidl_typesupport_introspection_cpp.so
librcl_logging_interface.so
librcl_logging_noop.so
librcl_yaml_param_parser.so
librcpputils.so
librcutils.so
librmw.so
librmw_dds_common.so
librmw_dds_common__rosidl_generator_c.so
librmw_dds_common__rosidl_typesupport_c.so
librmw_dds_common__rosidl_typesupport_cpp.so
librmw_dds_common__rosidl_typesupport_fastrtps_c.so
librmw_dds_common__rosidl_typesupport_fastrtps_cpp.so
librmw_dds_common__rosidl_typesupport_introspection_c.so
librmw_dds_common__rosidl_typesupport_introspection_cpp.so
librmw_fastrtps_cpp.so
librmw_fastrtps_shared_cpp.so
librmw_security_common.so
librosidl_buffer.so
librosidl_buffer_backend_registry.so
librosidl_dynamic_typesupport.so
librosidl_dynamic_typesupport_fastrtps.so
librosidl_runtime_c.so
librosidl_typesupport_c.so
librosidl_typesupport_cpp.so
librosidl_typesupport_fastrtps_c.so
librosidl_typesupport_fastrtps_cpp.so
librosidl_typesupport_introspection_c.so
librosidl_typesupport_introspection_cpp.so
libsensor_msgs__rosidl_generator_c.so
libsensor_msgs__rosidl_typesupport_c.so
libsensor_msgs__rosidl_typesupport_fastrtps_c.so
libservice_msgs__rosidl_generator_c.so
libservice_msgs__rosidl_typesupport_c.so
libservice_msgs__rosidl_typesupport_cpp.so
libservice_msgs__rosidl_typesupport_fastrtps_c.so
libservice_msgs__rosidl_typesupport_fastrtps_cpp.so
libservice_msgs__rosidl_typesupport_introspection_c.so
libservice_msgs__rosidl_typesupport_introspection_cpp.so
libstd_msgs__rosidl_generator_c.so
libstd_msgs__rosidl_typesupport_c.so
libstd_msgs__rosidl_typesupport_cpp.so
libstd_msgs__rosidl_typesupport_fastrtps_c.so
libstd_msgs__rosidl_typesupport_fastrtps_cpp.so
libstd_msgs__rosidl_typesupport_introspection_c.so
libstd_msgs__rosidl_typesupport_introspection_cpp.so
libtinyxml2.so
libtype_description_interfaces__rosidl_generator_c.so
libtype_description_interfaces__rosidl_typesupport_c.so
libtype_description_interfaces__rosidl_typesupport_cpp.so
libtype_description_interfaces__rosidl_typesupport_fastrtps_c.so
libtype_description_interfaces__rosidl_typesupport_fastrtps_cpp.so
libtype_description_interfaces__rosidl_typesupport_introspection_c.so
libtype_description_interfaces__rosidl_typesupport_introspection_cpp.so
```

Cyclone debug and release additionally fail on:

```text
libgeometry_msgs__rosidl_typesupport_introspection_c.so
libsensor_msgs__rosidl_typesupport_introspection_c.so
```

The two Cyclone DDS-specific libraries `libddsc.so` and `librmw_cyclonedds_cpp.so` passed the alignment check. The newly compiled bridge and `libc++_shared.so` also passed.

`libimage_processing_util_jni.so` is a blocker for source tracing: it is packaged and misaligned but was not found in the inspected ROS 2 prefix or the two `jniLibs` directories. Identify which Gradle/AAR or native dependency contributes it before planning a complete rebuild.

## 8. Root cause and fix boundary

The evidence points to **prebuilt native libraries**, not Gradle/AGP packaging or the app bridge:

1. The same prebuilt libraries remained byte-identical to the baseline APK.
2. The bridge built with NDK r28 passed with `0x4000` LOAD alignment.
3. 81 common prebuilt libraries continued to fail across both DDS APK variants; Cyclone had two extra failing introspection libraries.
4. All APK ZIP alignment checks passed independently.

A full fix requires compatible aligned builds/replacements for every failing library in each packaged variant, including all transitive native dependencies. Updating only the app bridge, Gradle, or APK ZIP alignment is insufficient.

## 9. Required continuation plan

No next implementation step is authorized by this diagnostic export alone. When resuming:

1. Recover the original `.repos` manifest, source checkouts, build cache, logs, or release archive checksums if possible. Otherwise obtain explicit approval to use a new candidate pinned source baseline; do not describe it as original provenance.
2. Trace `libimage_processing_util_jni.so` to its source/AAR and determine whether it can be rebuilt with a 16 KB-compatible toolchain.
3. Create a fresh isolated source/build/install prefix. Keep the existing ignored prefix and baseline APKs unchanged.
4. Select a consistent NDK r28+ toolchain and explicit Android API/ABI settings while preserving current Fast DDS and Cyclone `minSdk` values and behavior.
5. Rebuild all required common ROS 2/runtime/typesupport libraries and both DDS dependency paths with proper 16 KB ELF alignment. Preserve both backends and avoid changing topics, QoS, discovery configuration, sensor behavior, TCP telemetry, or ADB behavior unless separately approved.
6. Package all three variants and inspect every `.so` ELF LOAD segment, APK ZIP alignment, SHA-256, and provenance. No library may be omitted from the audit.
7. Run regression tests for both DDS variants, Wi-Fi/USB discovery, supported sensor messages and QoS, TCP telemetry, and ADB integration.
8. Validate runtime behavior on an actual 16 KB Android environment. The connected 4 KB phone may be used for ordinary regression checks, but cannot satisfy this criterion.
9. Only declare completion if static ELF checks, ZIP alignment, behavioral regression checks, and 16 KB runtime validation all pass.

The existing scripts `scripts/build_infra.sh`, `scripts/build_missing_deps.sh`, `scripts/build_rmw_fastrtps.sh`, and `scripts/build_rcl_only.sh` require `ROS2_SOURCE_DIR`; several install directly into `ROS2_ANDROID_PREFIX`. Do not run them against the protected original prefix.

## 10. Original and diagnostic artifact reference

Original baseline APKs:

- `app/build/outputs/apk/fastdds/debug/app-fastdds-debug.apk` — `edef03864b1c7d8768f00e0365600abaa8b00b940bcdf007b66b7e95361329eb`
- `app/build/outputs/apk/cyclone/debug/app-cyclone-debug.apk` — `0ac1baeb7a443101f8d389c7c8e2ab6ab8c5bd52371beddb7aef895275712615`
- `app/build/outputs/apk/cyclone/release/app-cyclone-release-unsigned.apk` — `48b8c51c02a9ebe56f3f7b5f63926ab74ef4cef937ecf857fb5231d3aeefbcfe`

Diagnostic APKs are under `/tmp/asr-android-16kb-diagnostic-20261016/app/build/outputs/apk/`. They are experimental outputs, not release artifacts and not 16 KB compatible.

Relevant project files:

- `app/build.gradle.kts` — SDK, flavors, ABI, and external CMake configuration.
- `app/src/main/cpp/CMakeLists.txt` — shared native library imports and DDS backend selection.
- `app/src/main/jniLibs/arm64-v8a/` — shared packaged native libraries.
- `app/src/cyclone/jniLibs/arm64-v8a/` — Cyclone-specific packaged native libraries.
- `docs/android-dds-backend-comparison.md` — recorded Cyclone version and observed behavior.
- `docs/BUILD_PREFIX_GUIDE.md` — ignored prefix and native build workflow notes.
