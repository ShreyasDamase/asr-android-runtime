#!/bin/bash
set -e

# Setup paths
export PATH="/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
TOOLCHAIN="/Users/shreyasdamase/Library/Android/sdk/ndk/27.1.12297006/build/cmake/android.toolchain.cmake"
PREFIX="/Users/shreyasdamase/ros2_android_install"
VENV_PY="/Users/shreyasdamase/rqt-venv/bin/python"
SOURCE_DIR="/Users/shreyasdamase/ros2_lyrical/src"
BUILD_DIR="/Users/shreyasdamase/ros2_android_build"

# Essential PYTHONPATH
export PYTHONPATH="$PREFIX/lib/python3.14/site-packages:/Users/shreyasdamase/rqt-venv/lib/python3.14/site-packages:$PYTHONPATH"

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
    "rosidl_typesupport_interface"
    "rosidl_buffer"
    "rosidl_runtime_c"
    "rosidl_runtime_cpp"
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

/Users/shreyasdamase/rqt-venv/bin/colcon \
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
        -DCMAKE_PREFIX_PATH="$PREFIX"
