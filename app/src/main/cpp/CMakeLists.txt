cmake_minimum_required(VERSION 3.22.1)

project("asr_ros2")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(ASR_DDS_BACKEND "fastdds" CACHE STRING "ROS 2 DDS backend for ASR")

# The native ROS 2 headers and common libraries are built separately from the
# JNI bridge. Supply their install prefix through the environment or CMake.
set(ROS2_ANDROID_PREFIX "$ENV{ROS2_ANDROID_PREFIX}" CACHE PATH
    "Android ROS 2 install prefix containing include/ and lib/")
if(NOT ROS2_ANDROID_PREFIX OR NOT IS_DIRECTORY "${ROS2_ANDROID_PREFIX}/include" OR
        NOT IS_DIRECTORY "${ROS2_ANDROID_PREFIX}/lib")
    message(FATAL_ERROR
        "Android ROS 2 prefix not found. Set ROS2_ANDROID_PREFIX to the cross-built ROS 2 install prefix (must contain include/ and lib/).")
endif()

file(GLOB ROS2_ANDROID_INCLUDE_DIRS LIST_DIRECTORIES true
    "${ROS2_ANDROID_PREFIX}/include/*")
include_directories(
    "${ROS2_ANDROID_PREFIX}/include"
    "${ROS2_ANDROID_PREFIX}/include/rcutils")

function(import_ros2_library target filename)
    add_library("${target}" SHARED IMPORTED)
    set_target_properties("${target}" PROPERTIES
        IMPORTED_LOCATION "${ROS2_ANDROID_PREFIX}/lib/${filename}"
        IMPORTED_NO_SONAME TRUE)
endfunction()

import_ros2_library(rcutils librcutils.so)
import_ros2_library(rosidl_runtime_c librosidl_runtime_c.so)
import_ros2_library(rosidl_buffer librosidl_buffer.so)
import_ros2_library(rosidl_dynamic_typesupport librosidl_dynamic_typesupport.so)
import_ros2_library(rosidl_typesupport_c librosidl_typesupport_c.so)
import_ros2_library(rmw librmw.so)
import_ros2_library(rcl librcl.so)
if(ASR_DDS_BACKEND STREQUAL "cyclone")
    set(CYCLONE_ANDROID_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../cyclone/jniLibs/arm64-v8a")
    # Cyclone DDS consumes ROSIDL introspection C type support. The generic
    # rosidl_typesupport_c handles resolve to null for Cyclone unless these
    # generated message introspection libraries are loaded into the APK.
    import_ros2_library(std_msgs_typesupport_introspection_c libstd_msgs__rosidl_typesupport_introspection_c.so)
    import_ros2_library(sensor_msgs_typesupport_introspection_c libsensor_msgs__rosidl_typesupport_introspection_c.so)
    import_ros2_library(geometry_msgs_typesupport_introspection_c libgeometry_msgs__rosidl_typesupport_introspection_c.so)
    add_library(rmw_cyclonedds_cpp SHARED IMPORTED)
    set_target_properties(rmw_cyclonedds_cpp PROPERTIES
        IMPORTED_LOCATION "${CYCLONE_ANDROID_LIB_DIR}/librmw_cyclonedds_cpp.so"
        IMPORTED_NO_SONAME TRUE)
    add_library(ddsc SHARED IMPORTED)
    set_target_properties(ddsc PROPERTIES
        IMPORTED_LOCATION "${CYCLONE_ANDROID_LIB_DIR}/libddsc.so"
        IMPORTED_NO_SONAME TRUE)
elseif(ASR_DDS_BACKEND STREQUAL "fastdds")
    import_ros2_library(rmw_fastrtps_cpp librmw_fastrtps_cpp.so)
    import_ros2_library(rmw_fastrtps_shared_cpp librmw_fastrtps_shared_cpp.so)
    import_ros2_library(fastdds libfastdds.so)
    import_ros2_library(fastcdr libfastcdr.so)
else()
    message(FATAL_ERROR "Unsupported ASR_DDS_BACKEND='${ASR_DDS_BACKEND}'")
endif()
import_ros2_library(std_msgs_generator_c libstd_msgs__rosidl_generator_c.so)
import_ros2_library(std_msgs_typesupport_c libstd_msgs__rosidl_typesupport_c.so)
import_ros2_library(rcl_interfaces_generator_c librcl_interfaces__rosidl_generator_c.so)
import_ros2_library(rcl_interfaces_typesupport_c librcl_interfaces__rosidl_typesupport_c.so)
import_ros2_library(type_description_generator_c libtype_description_interfaces__rosidl_generator_c.so)
import_ros2_library(type_description_typesupport_c libtype_description_interfaces__rosidl_typesupport_c.so)
import_ros2_library(geometry_msgs_generator_c libgeometry_msgs__rosidl_generator_c.so)
import_ros2_library(geometry_msgs_typesupport_c libgeometry_msgs__rosidl_typesupport_c.so)
import_ros2_library(sensor_msgs_generator_c libsensor_msgs__rosidl_generator_c.so)
import_ros2_library(sensor_msgs_typesupport_c libsensor_msgs__rosidl_typesupport_c.so)
import_ros2_library(sensor_msgs_fastrtps_c libsensor_msgs__rosidl_typesupport_fastrtps_c.so)
import_ros2_library(geometry_msgs_fastrtps_c libgeometry_msgs__rosidl_typesupport_fastrtps_c.so)

add_library(asr_ros2 SHARED
        NativeRos2Bridge.cpp)

target_compile_options(asr_ros2 PRIVATE
        "-idirafter${ROS2_ANDROID_PREFIX}/include/rmw")
list(REMOVE_ITEM ROS2_ANDROID_INCLUDE_DIRS
        "${ROS2_ANDROID_PREFIX}/include/rcutils"
        "${ROS2_ANDROID_PREFIX}/include/rmw")
foreach(ROS2_ANDROID_INCLUDE_DIR IN LISTS ROS2_ANDROID_INCLUDE_DIRS)
    target_compile_options(asr_ros2 PRIVATE
            "-idirafter${ROS2_ANDROID_INCLUDE_DIR}")
endforeach()

target_link_libraries(asr_ros2
        rcl
        rmw
        std_msgs_typesupport_c
        std_msgs_generator_c
        rcl_interfaces_typesupport_c
        rcl_interfaces_generator_c
        type_description_typesupport_c
        type_description_generator_c
        sensor_msgs_typesupport_c
        sensor_msgs_generator_c
        geometry_msgs_typesupport_c
        geometry_msgs_generator_c
        sensor_msgs_fastrtps_c
        geometry_msgs_fastrtps_c
        rosidl_typesupport_c
        rosidl_runtime_c
        rosidl_dynamic_typesupport
        rosidl_buffer
        rcutils
        log)

if(ASR_DDS_BACKEND STREQUAL "cyclone")
    target_compile_definitions(asr_ros2 PRIVATE
            ASR_RMW_IMPLEMENTATION="rmw_cyclonedds_cpp"
            ASR_USE_CYCLONE_DDS=1)
    target_link_libraries(asr_ros2
            rmw_cyclonedds_cpp
            ddsc
            "-Wl,--no-as-needed"
            std_msgs_typesupport_introspection_c
            sensor_msgs_typesupport_introspection_c
            geometry_msgs_typesupport_introspection_c
            "-Wl,--as-needed")
else()
    target_compile_definitions(asr_ros2 PRIVATE ASR_RMW_IMPLEMENTATION="rmw_fastrtps_cpp")
    target_link_libraries(asr_ros2 rmw_fastrtps_cpp rmw_fastrtps_shared_cpp fastdds fastcdr)
endif()
