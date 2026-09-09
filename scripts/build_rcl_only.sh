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

# Explicitly ignore problematic packages and everything we don't strictly need
IGNORE=(
    "rcpputils" "ament_index_cpp" "rosidl_runtime_cpp" "rosidl_generator_cpp"
    "rosidl_typesupport_cpp" "rosidl_typesupport_fastrtps_cpp"
    "rmw_fastrtps_cpp" "rmw_fastrtps_shared_cpp" "class_loader" "pluginlib"
    "fastcdr" "fastdds" "foonathan_memory_vendor" "iceoryx_hoofs" "iceoryx_posh"
    "iceoryx_binding_c" "cyclonedds" "rmw_cyclonedds_cpp" "zenoh_bridge_dds"
    "rmw_zenoh_cpp" "osrf_testing_tools_cpp" "performance_test_fixture"
    "ament_cmake_google_benchmark" "google_benchmark_vendor" "ament_cmake_gtest"
    "ament_cmake_gmock" "ament_cmake_pytest" "uncrustify_vendor"
    "ament_copyright" "ament_cpplint" "ament_flake8" "ament_lint_cmake"
    "ament_pep257" "ament_pycodestyle" "ament_xmllint" "ament_mypy"
    "ament_pyflakes" "ament_lint_common" "ament_lint_auto" "ament_cppcheck"
    "ament_uncrustify" "launch_testing" "mimick_vendor" "spdlog_vendor"
    "rti_connext_dds_cmake_module" "rmw_connextdds_common" "launch"
    "launch_xml" "launch_yaml" "rosidl_buffer_py" "rosidl_generator_py"
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
        -DAMENT_LINT_AUTO=OFF \
        -DCMAKE_PREFIX_PATH="$PREFIX" \
        -DFORCE_BUILD_VENDOR_PKG=ON \
        "${DIR_ARGS[@]}"
