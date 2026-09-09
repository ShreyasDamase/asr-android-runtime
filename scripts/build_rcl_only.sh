#!/bin/bash
set -e

# Setup paths
export PATH="/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
TOOLCHAIN="/Users/shreyasdamase/Library/Android/sdk/ndk/27.1.12297006/build/cmake/android.toolchain.cmake"
PREFIX="/Users/shreyasdamase/ros2_android_install"
VENV_PY="/Users/shreyasdamase/rqt-venv/bin/python"
SOURCE_DIR="/Users/shreyasdamase/ros2_lyrical/src"
BUILD_DIR="/Users/shreyasdamase/ros2_android_build"

# Essential PYTHONPATH for rosidl tools
export PYTHONPATH="$PREFIX/lib/python3.14/site-packages:/Users/shreyasdamase/rqt-venv/lib/python3.14/site-packages:$PYTHONPATH"
export AMENT_PREFIX_PATH="$PREFIX"

# Packages to build to reach rcl
PACKAGES=(
    "libyaml_vendor"
    "rosidl_typesupport_c"
    "type_description_interfaces"
    "service_msgs"
    "rcl_interfaces"
    "rcl_yaml_param_parser"
    "rcl"
)

# Explicitly ignore problematic C++ packages
IGNORE=(
    "rcpputils"
    "ament_index_cpp"
    "rosidl_runtime_cpp"
    "rosidl_generator_cpp"
    "rosidl_typesupport_cpp"
    "rosidl_typesupport_fastrtps_cpp"
    "rmw_fastrtps_cpp"
    "rmw_fastrtps_shared_cpp"
    "class_loader"
    "pluginlib"
)

# Generate DIR arguments for existing install
DIR_ARGS=()
for d in "$PREFIX"/share/*; do
    pkg=$(basename "$d")
    if [ -d "$d/cmake" ]; then
        DIR_ARGS+=("-D${pkg}_DIR=$d/cmake")
    fi
done

/Users/shreyasdamase/rqt-venv/bin/colcon \
    --log-base "$BUILD_DIR/log" \
    build \
    --merge-install \
    --build-base "$BUILD_DIR" \
    --install-base "$PREFIX" \
    --base-paths "$SOURCE_DIR" \
    --packages-up-to "${PACKAGES[@]}" \
    --packages-ignore "${IGNORE[@]}" \
    --cmake-args \
        -DPython3_EXECUTABLE="$VENV_PY" \
        -DPYTHON_EXECUTABLE="$VENV_PY" \
        -DAMENT_PYTHON_EXECUTABLE="$VENV_PY" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-30 \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_TESTING=OFF \
        -DCMAKE_PREFIX_PATH="$PREFIX" \
        -DFORCE_BUILD_VENDOR_PKG=ON \
        "${DIR_ARGS[@]}"
