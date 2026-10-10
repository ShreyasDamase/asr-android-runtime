# ROS 2 Android Native Build Prefix Guide

This guide explains the architecture, developer setup, and maintainer release workflow for the native ROS 2 Android cross-build prefix (`ros2_android_install`).

---

## 1. Overview & Architecture

### What is the Build Prefix?
The ASR Android Runtime uses native C++ components (`NativeRos2Bridge.cpp`) to interface with ROS 2 and DDS. To compile this native bridge via CMake and the Android NDK, the build system needs:
1. **C++ Header Files (`include/`)**: Header files for `rcl`, `rmw`, `sensor_msgs`, `std_msgs`, `rcutils`, etc.
2. **Native Libraries (`lib/`)**: Cross-compiled shared libraries (`.so`) for Android `arm64-v8a` target architecture.

The `ros2_android_install` directory is the output of the cross-compilation process where all necessary ROS 2 libraries and headers reside.

### Why is it not committed directly to Git?
- **Repository Bloat**: The compiled binary artifacts exceed hundreds of megabytes. Tracking binaries in Git degrades repository clone speeds and pushes against GitHub repository recommendations.
- **APK Bundling vs. Compilation**: The runtime libraries required on the physical device are already bundled under `app/src/main/jniLibs/arm64-v8a/`. Only developers **building from source** need the full `include/` headers and CMake link stubs.
- **Versioning Alignment**: The build prefix changes only when native ROS 2 packages, message types, or NDK versions are updated.

---

## 2. Developer Guide: Building from Source

If you only want to install and use the app on an Android phone, you **do not need** to build from source—simply download the pre-built `app-cyclone-debug.apk` from [GitHub Releases](https://github.com/ShreyasDamase/asr-android-runtime/releases/latest).

If you are contributing code or building the app yourself, follow these steps:

### Step 1: Download the Versioned Prefix
1. Navigate to the [GitHub Releases](https://github.com/ShreyasDamase/asr-android-runtime/releases) page for this repository.
2. Under the release corresponding to your branch/tag (e.g., `v1.0.0`), download:
   ```
   ros2_android_install-v1.0.0.zip
   ```

### Step 2: Extract the Archive
Extract the archive anywhere on your workstation. For example, in your home directory:
```bash
unzip ros2_android_install-v1.0.0.zip -d ~
```
This produces `~/ros2_android_install` containing `include/` and `lib/`.

### Step 3: Configure Project Path
The build system automatically checks your `local.properties` file before falling back to environment variables.

Open (or create) `local.properties` in the repository root and add:
```properties
ROS2_ANDROID_PREFIX=/Users/your-username/ros2_android_install
```
*(Replace `/Users/your-username/` with the absolute path on your computer).*

> **Tip**: You can alternatively export the environment variable in your shell profile:
> ```bash
> export ROS2_ANDROID_PREFIX="$HOME/ros2_android_install"
> ```

### Step 4: Build the Android App
Run Gradle to assemble the Cyclone DDS variant:
```bash
./gradlew :app:assembleCycloneDebug
```
For the Fast DDS comparison variant:
```bash
./gradlew :app:assembleFastddsDebug
```

The resulting APK will be placed in:
```
app/build/outputs/apk/cyclone/debug/app-cyclone-debug.apk
```

---

## 3. Maintainer Guide: Updating & Re-releasing the Prefix

As the project maintainer, you will periodically update the ROS 2 Android prefix when:
- Adding new ROS 2 interfaces or sensor message definitions.
- Upgrading the ROS 2 distribution or DDS middleware version.
- Upgrading the Android NDK toolchain version.

### Step 1: Cross-Compile Updated Packages
When native changes are required, use the cross-compilation scripts located in `scripts/`:
- `scripts/build_infra.sh`: Rebuilds core ament and rosidl infrastructure.
- `scripts/build_rmw_fastrtps.sh`: Rebuilds Fast DDS and RMW bindings.
- `scripts/build_missing_deps.sh`: Rebuilds dependent packages.

Ensure the build output directory contains the populated `include/` and `lib/` directories.

### Step 2: Package the Updated Prefix
We provide an automated helper script `scripts/package_prefix.sh` to package, compress, and generate SHA-256 checksums:

```bash
# Basic usage (auto-detects prefix from local.properties and defaults to v1.0.0):
./scripts/package_prefix.sh

# Or specify custom path and release version:
./scripts/package_prefix.sh /path/to/ros2_android_install v1.1.0 ./build/releases
```

The script produces:
1. `ros2_android_install-v<version>.zip`
2. `ros2_android_install-v<version>.zip.sha256`

### Step 3: Attach to GitHub Release
1. Create or edit your GitHub Release corresponding to the version tag (e.g., `v1.0.0`).
2. Attach:
   - `app-cyclone-debug.apk` (for end users)
   - `ros2_android_install-v<version>.zip` (for source developers)
   - *(Optional)* `ros2_android_install-v<version>.zip.sha256` for integrity validation.
3. In the Release notes, specify if the native build prefix changed or if the prior version can continue to be used.

---

## 4. Troubleshooting & FAQ

### CMake Error: "Android ROS 2 prefix not found"
```
CMake Error at CMakeLists.txt:15 (message):
  Android ROS 2 prefix not found. Set ROS2_ANDROID_PREFIX to the cross-built
  ROS 2 install prefix (must contain include/ and lib/).
```
- **Cause**: Neither `local.properties` nor the `ROS2_ANDROID_PREFIX` environment variable points to a valid directory containing both `include/` and `lib/`.
- **Solution**: Check your `local.properties` line: `ROS2_ANDROID_PREFIX=/absolute/path/to/ros2_android_install` (avoid relative paths).

### Gradle Error: `assembleCycloneExperimentDebug` not found

The current project defines the `cyclone` and `fastdds` DDS flavors and the standard `debug` and `release` build types. `CycloneExperimentDebug` is an obsolete variant name. Use:

```bash
./gradlew :app:assembleCycloneDebug
```

In Android Studio, run **Sync Project with Gradle Files**, then choose `app` → `cycloneDebug` in the **Build Variants** tool window. If an old `CycloneExperimentDebug` task remains in a saved Gradle run configuration, replace it with `:app:assembleCycloneDebug` or delete that obsolete run configuration.

### CMake Cache Inconsistencies
If you moved the prefix to a new directory and CMake still looks at the old path, clear the CMake build cache:
```bash
rm -rf app/.cxx app/build/intermediates/cxx
./gradlew :app:assembleCycloneDebug
```

### Missing C++ Headers during Compilation
If a specific header like `rcl/rcl.h` is reported missing:
- Verify that the extracted directory has `include/rcl/rcl.h`.
- Verify file permissions (`chmod -R u+r ~/ros2_android_install`).
