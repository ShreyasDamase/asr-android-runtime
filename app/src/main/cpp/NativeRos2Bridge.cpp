#include <jni.h>
#include <string>
#include <mutex>
#include <map>
#include <android/log.h>

#include <rcl/rcl.h>
#include <rcl/error_handling.h>
#include <std_msgs/msg/string.h>
#include <rosidl_runtime_c/string_functions.h>

#define TAG "NativeRos2Bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static rcl_context_t context;
static rcl_node_t node;
static bool node_ready = false;
static std::mutex node_mutex;

static std::map<std::string, rcl_publisher_t> publishers;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_initialize(JNIEnv *env, jobject thiz, jint domain_id, jstring j_node_name) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (rcl_context_is_valid(&context)) {
        LOGI("RCL context already valid");
        return JNI_TRUE;
    }

    rcl_init_options_t init_options = rcl_get_zero_initialized_init_options();
    rcl_ret_t ret = rcl_init_options_init(&init_options, rcl_get_default_allocator());
    if (ret != RCL_RET_OK) {
        LOGE("Failed to init options: %s", rcl_get_error_string().str);
        rcl_reset_error();
        return JNI_FALSE;
    }

    ret = rcl_init_options_set_domain_id(&init_options, (size_t)domain_id);
    if (ret != RCL_RET_OK) {
        LOGE("Failed to set domain ID: %s", rcl_get_error_string().str);
        rcl_reset_error();
        rcl_init_options_fini(&init_options);
        return JNI_FALSE;
    }

    context = rcl_get_zero_initialized_context();
    ret = rcl_init(0, nullptr, &init_options, &context);
    rcl_init_options_fini(&init_options);

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
        LOGI("Initializing RCL context automatically");
        rcl_init_options_t init_options = rcl_get_zero_initialized_init_options();
        rcl_init_options_init(&init_options, rcl_get_default_allocator());
        rcl_init_options_set_domain_id(&init_options, (size_t)domain_id);
        context = rcl_get_zero_initialized_context();
        rcl_init(0, nullptr, &init_options, &context);
        rcl_init_options_fini(&init_options);
    }

    if (!rcl_context_is_valid(&context)) {
        LOGE("RCL context remains invalid after auto-init");
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
    LOGI("ROS 2 node /%s started", node_name_str.c_str());
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

    auto it = publishers.find(topic_name);
    if (it == publishers.end()) {
        rcl_publisher_t pub = rcl_get_zero_initialized_publisher();
        rcl_publisher_options_t pub_options = rcl_publisher_get_default_options();
        const rosidl_message_type_support_t * ts = ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, String);

        rcl_ret_t ret = rcl_publisher_init(&pub, &node, ts, topic_name, &pub_options);
        if (ret != RCL_RET_OK) {
            LOGE("Failed to init publisher for %s: %s", topic_name, rcl_get_error_string().str);
            rcl_reset_error();
            env->ReleaseStringUTFChars(j_topic_name, topic_name);
            return JNI_FALSE;
        }
        publishers[topic_name] = pub;
        it = publishers.find(topic_name);
    }

    const char *message_str = env->GetStringUTFChars(j_message, nullptr);
    if (!message_str) {
        env->ReleaseStringUTFChars(j_topic_name, topic_name);
        return JNI_FALSE;
    }

    std_msgs__msg__String msg;
    std_msgs__msg__String__init(&msg);
    rosidl_runtime_c__String__assign(&msg.data, message_str);

    rcl_ret_t ret = rcl_publish(&it->second, &msg, nullptr);
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

JNIEXPORT void JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_shutdown(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(node_mutex);
    if (!node_ready) return;

    for (auto &pair : publishers) {
        rcl_publisher_fini(&pair.second, &node);
    }
    publishers.clear();

    rcl_node_fini(&node);
    rcl_shutdown(&context);
    rcl_context_fini(&context);
    node_ready = false;
    LOGI("ROS 2 node shutdown complete");
}

JNIEXPORT jboolean JNICALL
Java_org_asr_runtime_ros2_NativeRos2Bridge_isNodeReady(JNIEnv *env, jobject thiz) {
    return node_ready ? JNI_TRUE : JNI_FALSE;
}

}
