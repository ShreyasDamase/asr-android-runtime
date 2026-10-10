plugins {
    id("com.android.application")
    // id("org.jetbrains.kotlin.android") // Managed by top-level
}

android {
    namespace = "org.asr.runtime"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.asr.runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                // Pass ROS 2 install path to CMake if needed
                // arguments("-DCMAKE_PREFIX_PATH=/path/to/ros2_android/install")
            }
        }
    }

    flavorDimensions += "ddsBackend"
    productFlavors {
        create("fastdds") {
            dimension = "ddsBackend"
            buildConfigField("String", "ASR_DDS_BACKEND", "\"fastdds\"")
            externalNativeBuild {
                cmake {
                    arguments += "-DASR_DDS_BACKEND=fastdds"
                }
            }
        }
        create("cyclone") {
            dimension = "ddsBackend"
            applicationIdSuffix = ".cyclone"
            versionNameSuffix = "-cyclone"
            minSdk = 28
            buildConfigField("String", "ASR_DDS_BACKEND", "\"cyclone\"")
            externalNativeBuild {
                cmake {
                    arguments += "-DASR_DDS_BACKEND=cyclone"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        dataBinding = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    // Native ROS 2 support is provided by the ASR-owned JNI bridge and the platform-specific
    // rcl/rmw_fastrtps native libraries that are packaged for the target Android ABIs.
}
