#!/bin/bash
set -euo pipefail

# Build the real Android arm64 RMW implementation and only its ROS 2 dependencies.
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-27.1.12297006}"
TOOLCHAIN="${ANDROID_TOOLCHAIN_FILE:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION/build/cmake/android.toolchain.cmake}"
PREFIX="${ROS2_ANDROID_PREFIX:?Set ROS2_ANDROID_PREFIX to the Android ROS 2 install prefix}"
VENV_PY="${PYTHON_EXECUTABLE:-python3}"
SOURCE_DIR="${ROS2_SOURCE_DIR:?Set ROS2_SOURCE_DIR to the ROS 2 source workspace src directory}"
BUILD_DIR="${ROS2_ANDROID_BUILD_DIR:-$PWD/build/asr_android_native}/rmw_fastrtps"

export PYTHONPATH="$PREFIX/lib/python${ROS2_PYTHON_VERSION:-3.14}/site-packages${ROS2_PYTHON_SITE_PACKAGES:+:$ROS2_PYTHON_SITE_PACKAGES}${PYTHONPATH:+:$PYTHONPATH}"
# Do not activate this partial merged install: its unfinished lint/test packages
# have no package.sh hooks. CMake still consumes the completed libraries below.
unset AMENT_PREFIX_PATH

# Exact runtime closure. Existing libraries in PREFIX are never rebuilt.
PACKAGES=(
  rosidl_generator_cpp
  rosidl_generator_type_description
  rosidl_typesupport_introspection_c
  rosidl_typesupport_introspection_cpp
  rosidl_typesupport_c
  rosidl_typesupport_cpp
  rosidl_typesupport_fastrtps_cpp
  rosidl_typesupport_fastrtps_c
  rosidl_dynamic_typesupport_fastrtps
  rmw_security_common
  rmw_dds_common
  rosidl_buffer_backend_registry
  rmw_fastrtps_shared_cpp
  rmw_fastrtps_cpp
)

package_source() {
  find "$SOURCE_DIR" -path "*/$1/CMakeLists.txt" -print -quit
}

build_package() {
  local package="$1"
  local cmake_file
  cmake_file="$(package_source "$package")"
  if [ -z "$cmake_file" ]; then
    echo "Missing source for $package" >&2
    return 1
  fi
  local source_path="${cmake_file%/CMakeLists.txt}"
  local package_build="$BUILD_DIR/$package"
  local dir_args=()
  local d
  for d in "$PREFIX"/share/*; do
    if [ -d "$d/cmake" ]; then
      dir_args+=("-D$(basename "$d")_DIR=$d/cmake")
    fi
  done
  echo "=== Building $package ==="
  cmake -S "$source_path" -B "$package_build" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_PREFIX_PATH="$PREFIX" \
    -Dfastcdr_DIR="$PREFIX/lib/cmake/fastcdr" \
    -Dfoonathan_memory_DIR="$PREFIX/lib/foonathan_memory/cmake" \
    -DPython3_EXECUTABLE="$VENV_PY" \
    -DPYTHON_EXECUTABLE="$VENV_PY" \
    -DAMENT_PYTHON_EXECUTABLE="$VENV_PY" \
    -DBUILD_TESTING=OFF \
    "${dir_args[@]}"
  cmake --build "$package_build"
  cmake --install "$package_build"
}

for package in "${PACKAGES[@]}"; do
  build_package "$package"
done
