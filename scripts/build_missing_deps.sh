#!/bin/bash
set -e

# Setup paths
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-27.1.12297006}"
TOOLCHAIN="${ANDROID_TOOLCHAIN_FILE:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION/build/cmake/android.toolchain.cmake}"
PREFIX="${ROS2_ANDROID_PREFIX:?Set ROS2_ANDROID_PREFIX to the Android ROS 2 install prefix}"
VENV_PY="${PYTHON_EXECUTABLE:-python3}"
SOURCE_DIR="${ROS2_SOURCE_DIR:?Set ROS2_SOURCE_DIR to the ROS 2 source workspace src directory}"
BUILD_DIR="${ROS2_ANDROID_BUILD_DIR:-$PWD/build/asr_android_native}/missing_deps"

# Essential PYTHONPATH for rosidl tools
export PYTHONPATH="$PREFIX/lib/python${ROS2_PYTHON_VERSION:-3.14}/site-packages${ROS2_PYTHON_SITE_PACKAGES:+:$ROS2_PYTHON_SITE_PACKAGES}${PYTHONPATH:+:$PYTHONPATH}"
export AMENT_PREFIX_PATH="$PREFIX"

# Packages to ignore (strictly avoiding C++ complexity for rcl)
IGNORE=(
    "rcpputils" "ament_index_cpp" "rosidl_runtime_cpp" "class_loader" "pluginlib"
    "fastcdr" "fastdds" "foonathan_memory_vendor"
    "iceoryx_hoofs" "iceoryx_posh" "iceoryx_binding_c" "cyclonedds"
    "rmw_cyclonedds_cpp" "rmw_connextdds" "rmw_connextdds_common"
    "rmw_connextddsmicro" "rti_connext_dds_cmake_module"
    "ament_flake8" "ament_pep257" "ament_copyright" "ament_cmake_vendor_package"
    "ament_lint_auto" "ament_xmllint" "ament_cpplint" "ament_lint_cmake"
    "ament_pycodestyle" "ament_cmake_lint_cmake" "ament_cppcheck"
    "ament_cmake_copyright" "uncrustify_vendor" "ament_cmake_cppcheck"
    "ament_cmake_cpplint" "ament_cmake_flake8" "ament_cmake_pep257"
    "ament_cmake_xmllint" "ament_uncrustify" "ament_cmake_uncrustify"
    "ament_lint_common" "ament_pyflakes" "ament_cmake_pyflakes"
    "ament_cmake_mypy" "ament_mypy" "ament_cmake_python_test"
    "launch" "launch_xml" "launch_yaml" "launch_testing" "launch_testing_ament_cmake"
    "performance_test_fixture" "mimick_vendor" "ament_cmake_google_benchmark"
    "console_bridge_vendor" "spdlog_vendor" "zenoh_cpp_vendor" "libyaml_vendor"
    "rosidl_buffer_py" "rosidl_generator_py" "rclpy" "sensor_msgs_py" "rqt"
    "osrf_testing_tools_cpp" "test_msgs" "test_interface_files"
    "ament_cmake_gtest" "ament_cmake_gmock" "ament_cmake_pytest"
    "action_tutorials_py" "demo_nodes_py" "examples_rclpy_executors"
    "examples_rclpy_guard_conditions" "examples_rclpy_minimal_action_client"
    "examples_rclpy_minimal_action_server" "examples_rclpy_minimal_client"
    "examples_rclpy_minimal_publisher" "examples_rclpy_minimal_service"
    "examples_rclpy_minimal_subscriber" "examples_rclpy_pointcloud_publisher"
    "examples_tf2_py" "launch_pytest" "launch_ros" "launch_testing_ros"
    "ros2action" "ros2bag" "ros2cli" "ros2component" "ros2doctor"
    "ros2interface" "ros2launch" "ros2lifecycle" "ros2multicast"
    "ros2node" "ros2param" "ros2pkg" "ros2plugin" "ros2run"
    "ros2service" "ros2topic" "ros2trace" "rosidl_runtime_py"
    "rqt_action" "rqt_bag" "rqt_bag_plugins" "rqt_console" "rqt_graph"
    "rqt_gui" "rqt_gui_cpp" "rqt_gui_py" "rqt_msg" "rqt_plot"
    "rqt_publisher" "rqt_py_common" "rqt_py_console" "rqt_reconfigure"
    "rqt_service_caller" "rqt_shell" "rqt_srv" "rqt_topic" "tf2_ros_py"
    "tf2_tools" "topic_monitor" "tracetools_launch" "tracetools_read"
    "tracetools_test" "tracetools_trace" "rosidl_generator_rs"
)

# Packages we want to build
PACKAGES=(
    "rcl"
)

# Generate DIR arguments for EVERYTHING in share
DIR_ARGS=()
for d in "$PREFIX"/share/*; do
    pkg=$(basename "$d")
    if [ -d "$d/cmake" ]; then
        DIR_ARGS+=("-D${pkg}_DIR=$d/cmake")
    fi
done

colcon \
    --log-base "$BUILD_DIR/log" \
    build \
    --merge-install \
    --build-base "$BUILD_DIR" \
    --install-base "$PREFIX" \
    --base-paths "$SOURCE_DIR" \
    --packages-up-to "${PACKAGES[@]}" \
    --packages-ignore "${IGNORE[@]}" \
    --cmake-clean-cache \
    --cmake-args \
        -DPython3_EXECUTABLE="$VENV_PY" \
        -DPYTHON_EXECUTABLE="$VENV_PY" \
        -DAMENT_PYTHON_EXECUTABLE="$VENV_PY" \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-26 \
        -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_TESTING=OFF \
        -DCMAKE_PREFIX_PATH="$PREFIX" \
        -DFORCE_BUILD_VENDOR_PKG=ON \
        "${DIR_ARGS[@]}"
