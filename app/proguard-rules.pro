# JNI entry points are resolved by their class and method names in NativeRos2Bridge.cpp.
# Keep the bridge and its methods stable if R8 is enabled for a future release.
-keep class org.asr.runtime.ros2.NativeRos2Bridge { *; }

# Preserve names and descriptors for any additional Java/Kotlin native methods.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
