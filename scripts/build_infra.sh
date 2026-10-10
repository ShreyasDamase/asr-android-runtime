#!/bin/bash
set -e

# Setup paths
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-27.1.12297006}"
TOOLCHAIN="${ANDROID_TOOLCHAIN_FILE:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION/build/cmake/android.toolchain.cmake}"
PREFIX="${ROS2_ANDROID_PREFIX:?Set ROS2_ANDROID_PREFIX to the Android ROS 2 install prefix}"
VENV_PY="${PYTHON_EXECUTABLE:-python3}"
SOURCE_DIR="${ROS2_SOURCE_DIR:?Set ROS2_SOURCE_DIR to the ROS 2 source workspace src directory}"
BUILD_DIR="${ROS2_ANDROID_BUILD_DIR:-$PWD/build/asr_android_native}/infra"

# Essential PYTHONPATH
export PYTHONPATH="$PREFIX/lib/python${ROS2_PYTHON_VERSION:-3.14}/site-packages${ROS2_PYTHON_SITE_PACKAGES:+:$ROS2_PYTHON_SITE_PACKAGES}${PYTHONPATH:+:$PYTHONPATH}"

PACKAGES=(
    "ament_package"
    "ament_cmake_core"
    "ament_cmake_libraries"
    "ament_cmake_version"
    "ament_cmake_export_definitions"
    "ament_cmake_export_dependencies"
    "ament_cmake_export_include_directories"
    "ament_cmake_export_libraries"
    "ament_cmake_export_link_flags"
    "ament_cmake_export_targets"
    "ament_cmake_target_dependencies"
    "ament_cmake_test"
    "ament_cmake_python"
    "ament_cmake_gen_version_h"
    "ament_cmake_gtest"
    "ament_cmake_gmock"
    "ament_cmake_pytest"
    "ament_cmake"
    "ament_cmake_ros_core"
    "ament_cmake_ros"
    "rosidl_cli"
    "rosidl_adapter"
    "rosidl_parser"
    "rosidl_generator_type_description"
    "rosidl_pycommon"
    "rosidl_cmake"
    "rosidl_typesupport_interface"
    "rosidl_buffer"
    "rosidl_runtime_c"
    "rosidl_runtime_cpp"
    "rosidl_typesupport_introspection_c"
    "rosidl_typesupport_introspection_cpp"
)

IGNORE=(
    "ament_flake8" "ament_pep257" "ament_copyright" "ament_cmake_vendor_package"
    "ament_lint_auto" "ament_xmllint" "ament_cpplint" "ament_lint_cmake"
    "ament_pycodestyle" "ament_cmake_lint_cmake" "ament_cppcheck"
    "ament_cmake_copyright" "uncrustify_vendor" "ament_cmake_cppcheck"
    "ament_cmake_cpplint" "ament_cmake_flake8" "ament_cmake_pep257"
    "ament_cmake_xmllint" "ament_uncrustify" "ament_cmake_uncrustify"
    "ament_lint_common" "ament_pyflakes" "ament_cmake_pyflakes"
    "ament_cmake_mypy" "ament_mypy"
    "launch" "launch_xml" "launch_yaml" "launch_testing" "launch_testing_ament_cmake"
    "performance_test_fixture" "mimick_vendor" "ament_cmake_google_benchmark"
)

colcon \
    --log-base "$BUILD_DIR/log" \
    build \
    --merge-install \
    --build-base "$BUILD_DIR" \
    --install-base "$PREFIX" \
    --base-paths "$SOURCE_DIR" \
    --packages-select "${PACKAGES[@]}" \
    --packages-ignore "${IGNORE[@]}" \
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
        -Dament_cmake_DIR="$PREFIX/share/ament_cmake/cmake" \
        -Dament_cmake_core_DIR="$PREFIX/share/ament_cmake_core/cmake" \
        -Dament_cmake_libraries_DIR="$PREFIX/share/ament_cmake_libraries/cmake" \
        -Dament_cmake_export_definitions_DIR="$PREFIX/share/ament_cmake_export_definitions/cmake" \
        -Dament_cmake_export_dependencies_DIR="$PREFIX/share/ament_cmake_export_dependencies/cmake" \
        -Dament_cmake_export_include_directories_DIR="$PREFIX/share/ament_cmake_export_include_directories/cmake" \
        -Dament_cmake_export_libraries_DIR="$PREFIX/share/ament_cmake_export_libraries/cmake" \
        -Dament_cmake_export_link_flags_DIR="$PREFIX/share/ament_cmake_export_link_flags/cmake" \
        -Dament_cmake_export_targets_DIR="$PREFIX/share/ament_cmake_export_targets/cmake" \
        -Dament_cmake_python_DIR="$PREFIX/share/ament_cmake_python/cmake" \
        -Dament_cmake_target_dependencies_DIR="$PREFIX/share/ament_cmake_target_dependencies/cmake" \
        -Dament_cmake_test_DIR="$PREFIX/share/ament_cmake_test/cmake" \
        -Dament_cmake_version_DIR="$PREFIX/share/ament_cmake_version/cmake" \
        -Dament_cmake_gen_version_h_DIR="$PREFIX/share/ament_cmake_gen_version_h/cmake" \
        -Dament_cmake_gtest_DIR="$PREFIX/share/ament_cmake_gtest/cmake" \
        -Dament_cmake_pytest_DIR="$PREFIX/share/ament_cmake_pytest/cmake" \
        -Dament_cmake_ros_core_DIR="$PREFIX/share/ament_cmake_ros_core/cmake"
