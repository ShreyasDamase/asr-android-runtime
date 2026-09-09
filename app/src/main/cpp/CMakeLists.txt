cmake_minimum_required(VERSION 3.22.1)

project("asr_ros2")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define the Android ROS 2 install prefix
set(ROS2_ANDROID_PREFIX "/Users/shreyasdamase/ros2_android_install")

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
import_ros2_library(rmw_fastrtps_cpp librmw_fastrtps_cpp.so)
import_ros2_library(rmw_fastrtps_shared_cpp librmw_fastrtps_shared_cpp.so)
import_ros2_library(fastdds libfastdds.so)
import_ros2_library(fastcdr libfastcdr.so)
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
        rmw_fastrtps_cpp
        rmw_fastrtps_shared_cpp
        fastdds
        fastcdr
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
