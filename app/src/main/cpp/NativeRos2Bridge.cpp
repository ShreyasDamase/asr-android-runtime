#include <jni.h>
#include <string>
#include <mutex>
#include <map>
#include <algorithm>
#include <android/log.h>

#include <rcl/rcl.h>
#include <rcl/error_handling.h>
#include <std_msgs/msg/string.h>
#include <sensor_msgs/msg/imu.h>
#include <sensor_msgs/msg/nav_sat_fix.h>
#include <sensor_msgs/msg/battery_state.h>
#include <sensor_msgs/msg/illuminance.h>
#include <sensor_msgs/msg/fluid_pressure.h>
#include <sensor_msgs/msg/temperature.h>
#include <sensor_msgs/msg/relative_humidity.h>
#include <sensor_msgs/msg/compressed_image.h>
#include <sensor_msgs/msg/camera_info.h>
#include <sensor_msgs/msg/detail/imu__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/nav_sat_fix__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/battery_state__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/illuminance__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/fluid_pressure__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/temperature__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/relative_humidity__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/compressed_image__rosidl_typesupport_fastrtps_c.h>
#include <sensor_msgs/msg/detail/camera_info__rosidl_typesupport_fastrtps_c.h>
#include <rosidl_runtime_c/string_functions.h>
#include <rosidl_runtime_c/primitives_sequence_functions.h>

#define TAG "NativeRos2Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static rcl_context_t context;
static rcl_node_t node;
static bool node_ready = false;
static std::mutex node_mutex;

static std::map<std::string, rcl_publisher_t> publishers;

static bool ensure_publisher(const char *topic_name, const rosidl_message_type_support_t *ts, rcl_publisher_t **out) {
    auto it = publishers.find(topic_name);
    if (it == publishers.end()) {
        rcl_publisher_t pub = rcl_get_zero_initialized_publisher();
        rcl_publisher_options_t options = rcl_publisher_get_default_options();
        if (rcl_publisher_init(&pub, &node, ts, topic_name, &options) != RCL_RET_OK) {
            LOGE("Failed to init publisher for %s: %s", topic_name, rcl_get_error_string().str);
            rcl_reset_error();
            return false;
        }
        publishers.emplace(topic_name, pub);
        it = publishers.find(topic_name);
    }
    *out = &it->second;
    return true;
}

static void set_stamp(std_msgs__msg__Header *header, jlong timestamp_ns) {
    header->stamp.sec = static_cast<int32_t>(timestamp_ns / 1000000000LL);
    header->stamp.nanosec = static_cast<uint32_t>(timestamp_ns % 1000000000LL);
    rosidl_runtime_c__String__assign(&header->frame_id, "asr_android");
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_initialize(JNIEnv *env, jobject thiz, jint domain_id, jstring j_node_name) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (rcl_context_is_valid(&context)) {
        LOGI("RCL context already valid; skipping init");
        return JNI_TRUE;
    }

    // Ensure context is clean before init
    context = rcl_get_zero_initialized_context();

    rcl_init_options_t init_options = rcl_get_zero_initialized_init_options();
    if (rcl_init_options_init(&init_options, rcl_get_default_allocator()) != RCL_RET_OK) {
        LOGE("Failed to init options: %s", rcl_get_error_string().str);
        rcl_reset_error();
        return JNI_FALSE;
    }

    if (rcl_init_options_set_domain_id(&init_options, (size_t)domain_id) != RCL_RET_OK) {
        LOGE("Failed to set domain ID: %s", rcl_get_error_string().str);
        rcl_reset_error();
        (void)rcl_init_options_fini(&init_options);
        return JNI_FALSE;
    }

    rcl_ret_t ret = rcl_init(0, nullptr, &init_options, &context);
    (void)rcl_init_options_fini(&init_options);

    if (ret != RCL_RET_OK) {
        LOGE("Failed to rcl_init: %s", rcl_get_error_string().str);
        rcl_reset_error();
        return JNI_FALSE;
    }

    LOGI("RCL initialized for domain %d", domain_id);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_startNode(JNIEnv *env, jobject thiz, jint domain_id, jstring j_node_name) {
    std::lock_guard<std::mutex> lock(node_mutex);

    if (!rcl_context_is_valid(&context)) {
        LOGI("Context invalid; performing automatic RCL initialization");
        context = rcl_get_zero_initialized_context();
        rcl_init_options_t init_options = rcl_get_zero_initialized_init_options();
        if (rcl_init_options_init(&init_options, rcl_get_default_allocator()) == RCL_RET_OK) {
            if (rcl_init_options_set_domain_id(&init_options, (size_t)domain_id) == RCL_RET_OK) {
                rcl_ret_t ret = rcl_init(0, nullptr, &init_options, &context);
                (void)rcl_init_options_fini(&init_options);
                if (ret != RCL_RET_OK) {
                    LOGE("Auto-init failed: %s", rcl_get_error_string().str);
                    rcl_reset_error();
                    return JNI_FALSE;
                }
            } else {
                (void)rcl_init_options_fini(&init_options);
            }
        }
    }

    if (!rcl_context_is_valid(&context)) {
        LOGE("RCL context remains invalid after auto-init attempt");
        return JNI_FALSE;
    }

    const char *node_name_chars = env->GetStringUTFChars(j_node_name, nullptr);
    if (!node_name_chars) return JNI_FALSE;

    node = rcl_get_zero_initialized_node();
    rcl_node_options_t node_options = rcl_node_get_default_options();

    rcl_ret_t ret = rcl_node_init(&node, node_name_chars, "", &context, &node_options);
    std::string node_name_str(node_name_chars);
    env->ReleaseStringUTFChars(j_node_name, node_name_chars);

    if (ret != RCL_RET_OK) {
        LOGE("Failed to init node /%s: %s", node_name_str.c_str(), rcl_get_error_string().str);
        rcl_reset_error();
        return JNI_FALSE;
    }

    node_ready = true;
    LOGI("ROS 2 node /%s started successfully", node_name_str.c_str());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_publishString(JNIEnv *env, jobject thiz, jstring j_topic_name, jstring j_message) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) {
        LOGE("Cannot publish: node not ready");
        return JNI_FALSE;
    }

    const char *topic_name = env->GetStringUTFChars(j_topic_name, nullptr);
    if (!topic_name) return JNI_FALSE;

    rcl_publisher_t *publisher = nullptr;
    if (!ensure_publisher(topic_name, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, String), &publisher)) {
        env->ReleaseStringUTFChars(j_topic_name, topic_name);
        return JNI_FALSE;
    }

    const char *message_str = env->GetStringUTFChars(j_message, nullptr);
    if (!message_str) {
        env->ReleaseStringUTFChars(j_topic_name, topic_name);
        return JNI_FALSE;
    }

    std_msgs__msg__String msg;
    std_msgs__msg__String__init(&msg);
    rosidl_runtime_c__String__assign(&msg.data, message_str);

    rcl_ret_t ret = rcl_publish(publisher, &msg, nullptr);
    std_msgs__msg__String__fini(&msg);

    env->ReleaseStringUTFChars(j_topic_name, topic_name);
    env->ReleaseStringUTFChars(j_message, message_str);

    if (ret != RCL_RET_OK) {
        LOGE("Failed to publish message: %s", rcl_get_error_string().str);
        rcl_reset_error();
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_publishImu(JNIEnv *env, jobject, jstring j_topic,
                                                       jdoubleArray j_accel, jdoubleArray j_gyro,
                                                       jdoubleArray j_orientation, jlong timestamp_ns) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) return JNI_FALSE;
    const char *topic = env->GetStringUTFChars(j_topic, nullptr);
    jdouble *accel = env->GetDoubleArrayElements(j_accel, nullptr);
    jdouble *gyro = env->GetDoubleArrayElements(j_gyro, nullptr);
    jdouble *orientation = env->GetDoubleArrayElements(j_orientation, nullptr);
    rcl_publisher_t *publisher = nullptr;
    bool ok = topic && accel && gyro && orientation &&
              ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, Imu)(), &publisher);
    if (ok) {
        sensor_msgs__msg__Imu msg;
        sensor_msgs__msg__Imu__init(&msg);
        set_stamp(&msg.header, timestamp_ns);
        std::fill(std::begin(msg.orientation_covariance), std::end(msg.orientation_covariance), 0.0);
        std::fill(std::begin(msg.angular_velocity_covariance), std::end(msg.angular_velocity_covariance), 0.0);
        std::fill(std::begin(msg.linear_acceleration_covariance), std::end(msg.linear_acceleration_covariance), 0.0);
        msg.orientation.x = orientation[0]; msg.orientation.y = orientation[1];
        msg.orientation.z = orientation[2]; msg.orientation.w = orientation[3];
        msg.angular_velocity.x = gyro[0]; msg.angular_velocity.y = gyro[1]; msg.angular_velocity.z = gyro[2];
        msg.linear_acceleration.x = accel[0]; msg.linear_acceleration.y = accel[1]; msg.linear_acceleration.z = accel[2];
        msg.orientation_covariance[0] = -1.0;
        msg.angular_velocity_covariance[0] = -1.0;
        msg.linear_acceleration_covariance[0] = -1.0;
        ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK;
        sensor_msgs__msg__Imu__fini(&msg);
    }
    if (accel) env->ReleaseDoubleArrayElements(j_accel, accel, JNI_ABORT);
    if (gyro) env->ReleaseDoubleArrayElements(j_gyro, gyro, JNI_ABORT);
    if (orientation) env->ReleaseDoubleArrayElements(j_orientation, orientation, JNI_ABORT);
    if (topic) env->ReleaseStringUTFChars(j_topic, topic);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_publishNavSatFix(JNIEnv *env, jobject, jstring j_topic,
                                                             jdouble latitude, jdouble longitude,
                                                             jdouble altitude, jfloat accuracy,
                                                             jlong timestamp_ns) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) return JNI_FALSE;
    const char *topic = env->GetStringUTFChars(j_topic, nullptr);
    rcl_publisher_t *publisher = nullptr;
    bool ok = topic && ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, NavSatFix)(), &publisher);
    if (ok) {
        sensor_msgs__msg__NavSatFix msg;
        sensor_msgs__msg__NavSatFix__init(&msg);
        set_stamp(&msg.header, timestamp_ns);
        msg.latitude = latitude; msg.longitude = longitude; msg.altitude = altitude;
        msg.position_covariance[0] = static_cast<double>(accuracy) * accuracy;
        msg.position_covariance[4] = msg.position_covariance[0];
        msg.position_covariance[8] = msg.position_covariance[0];
        msg.position_covariance_type = sensor_msgs__msg__NavSatFix__COVARIANCE_TYPE_APPROXIMATED;
        ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK;
        sensor_msgs__msg__NavSatFix__fini(&msg);
    }
    if (topic) env->ReleaseStringUTFChars(j_topic, topic);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_publishBattery(JNIEnv *env, jobject, jstring j_topic,
                                                            jfloat percentage, jfloat temperature,
                                                            jfloat voltage, jboolean charging) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) return JNI_FALSE;
    const char *topic = env->GetStringUTFChars(j_topic, nullptr);
    rcl_publisher_t *publisher = nullptr;
    bool ok = topic && ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, BatteryState)(), &publisher);
    if (ok) {
        sensor_msgs__msg__BatteryState msg;
        sensor_msgs__msg__BatteryState__init(&msg);
        set_stamp(&msg.header, 0);
        msg.percentage = percentage / 100.0f;
        msg.temperature = temperature;
        msg.voltage = voltage / 1000.0f;
        msg.power_supply_status = charging ? sensor_msgs__msg__BatteryState__POWER_SUPPLY_STATUS_CHARGING
                                           : sensor_msgs__msg__BatteryState__POWER_SUPPLY_STATUS_DISCHARGING;
        ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK;
        sensor_msgs__msg__BatteryState__fini(&msg);
    }
    if (topic) env->ReleaseStringUTFChars(j_topic, topic);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_publishScalar(JNIEnv *env, jobject, jstring j_topic,
                                                          jdouble value, jlong timestamp_ns, jint kind) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) return JNI_FALSE;
    const char *topic = env->GetStringUTFChars(j_topic, nullptr);
    bool ok = false;
    if (topic && kind == 0) {
        rcl_publisher_t *publisher = nullptr; ok = ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, Illuminance)(), &publisher);
        if (ok) { sensor_msgs__msg__Illuminance msg; sensor_msgs__msg__Illuminance__init(&msg); set_stamp(&msg.header, timestamp_ns); msg.illuminance = value; ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK; sensor_msgs__msg__Illuminance__fini(&msg); }
    } else if (topic && kind == 1) {
        rcl_publisher_t *publisher = nullptr; ok = ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, FluidPressure)(), &publisher);
        if (ok) { sensor_msgs__msg__FluidPressure msg; sensor_msgs__msg__FluidPressure__init(&msg); set_stamp(&msg.header, timestamp_ns); msg.fluid_pressure = value * 100.0; ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK; sensor_msgs__msg__FluidPressure__fini(&msg); }
    } else if (topic && kind == 2) {
        rcl_publisher_t *publisher = nullptr; ok = ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, Temperature)(), &publisher);
        if (ok) { sensor_msgs__msg__Temperature msg; sensor_msgs__msg__Temperature__init(&msg); set_stamp(&msg.header, timestamp_ns); msg.temperature = value; ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK; sensor_msgs__msg__Temperature__fini(&msg); }
    } else if (topic && kind == 3) {
        rcl_publisher_t *publisher = nullptr; ok = ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, RelativeHumidity)(), &publisher);
        if (ok) { sensor_msgs__msg__RelativeHumidity msg; sensor_msgs__msg__RelativeHumidity__init(&msg); set_stamp(&msg.header, timestamp_ns); msg.relative_humidity = value / 100.0; ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK; sensor_msgs__msg__RelativeHumidity__fini(&msg); }
    }
    if (topic) env->ReleaseStringUTFChars(j_topic, topic);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_publishCompressedImage(JNIEnv *env, jobject, jstring j_topic,
                                                                    jbyteArray j_data, jstring j_format,
                                                                    jlong timestamp_ns) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) return JNI_FALSE;
    const char *topic = env->GetStringUTFChars(j_topic, nullptr);
    const char *format = env->GetStringUTFChars(j_format, nullptr);
    rcl_publisher_t *publisher = nullptr;
    bool ok = topic && format && ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, CompressedImage)(), &publisher);
    if (ok) {
        jsize size = env->GetArrayLength(j_data);
        sensor_msgs__msg__CompressedImage msg;
        sensor_msgs__msg__CompressedImage__init(&msg);
        set_stamp(&msg.header, timestamp_ns);
        rosidl_runtime_c__String__assign(&msg.format, format);
        rosidl_runtime_c__uint8__Sequence__init(&msg.data, static_cast<size_t>(size));
        env->GetByteArrayRegion(j_data, 0, size, reinterpret_cast<jbyte *>(msg.data.data));
        ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK;
        sensor_msgs__msg__CompressedImage__fini(&msg);
    }
    if (topic) env->ReleaseStringUTFChars(j_topic, topic);
    if (format) env->ReleaseStringUTFChars(j_format, format);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_publishCameraInfo(JNIEnv *env, jobject, jstring j_topic,
                                                              jint width, jint height, jlong timestamp_ns) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) return JNI_FALSE;
    const char *topic = env->GetStringUTFChars(j_topic, nullptr);
    rcl_publisher_t *publisher = nullptr;
    bool ok = topic && ensure_publisher(topic, ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(rosidl_typesupport_fastrtps_c, sensor_msgs, msg, CameraInfo)(), &publisher);
    if (ok) {
        sensor_msgs__msg__CameraInfo msg;
        sensor_msgs__msg__CameraInfo__init(&msg);
        set_stamp(&msg.header, timestamp_ns);
        msg.width = static_cast<uint32_t>(width);
        msg.height = static_cast<uint32_t>(height);
        ok = rcl_publish(publisher, &msg, nullptr) == RCL_RET_OK;
        sensor_msgs__msg__CameraInfo__fini(&msg);
    }
    if (topic) env->ReleaseStringUTFChars(j_topic, topic);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_shutdown(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready && !rcl_context_is_valid(&context)) {
        LOGI("Shutdown requested but ROS 2 already idle; skipping");
        return;
    }

    LOGI("Shutting down ROS 2 node and context...");

    for (auto &pair : publishers) {
        if (rcl_publisher_fini(&pair.second, &node) != RCL_RET_OK) {
            LOGE("Error finalizing publisher for %s", pair.first.c_str());
        }
    }
    publishers.clear();

    if (node_ready) {
        if (rcl_node_fini(&node) != RCL_RET_OK) {
            LOGE("Error finalizing node: %s", rcl_get_error_string().str);
        }
        node = rcl_get_zero_initialized_node();
    }

    if (rcl_context_is_valid(&context)) {
        if (rcl_shutdown(&context) != RCL_RET_OK) {
            LOGE("Error in rcl_shutdown: %s", rcl_get_error_string().str);
        }
        if (rcl_context_fini(&context) != RCL_RET_OK) {
            LOGE("Error in rcl_context_fini: %s", rcl_get_error_string().str);
        }
        context = rcl_get_zero_initialized_context();
    }

    node_ready = false;
    LOGI("ROS 2 shutdown sequence complete");
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_isNodeReady(JNIEnv *env, jobject thiz) {
    return node_ready ? JNI_TRUE : JNI_FALSE;
}

}
