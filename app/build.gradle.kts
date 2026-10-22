import java.util.Properties

plugins {
    id("com.android.application")
    // id("org.jetbrains.kotlin.android") // Managed by top-level
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val ros2AndroidPrefix: String = providers.gradleProperty("ROS2_ANDROID_PREFIX").orNull
    ?: System.getenv("ROS2_ANDROID_PREFIX")
    ?: localProps.getProperty("ROS2_ANDROID_PREFIX")
    ?: ""
val releaseSigningValues = listOf(
    localProps.getProperty("ASR_RELEASE_STORE_FILE") ?: System.getenv("ASR_RELEASE_STORE_FILE"),
    localProps.getProperty("ASR_RELEASE_STORE_PASSWORD") ?: System.getenv("ASR_RELEASE_STORE_PASSWORD"),
    localProps.getProperty("ASR_RELEASE_KEY_ALIAS") ?: System.getenv("ASR_RELEASE_KEY_ALIAS"),
    localProps.getProperty("ASR_RELEASE_KEY_PASSWORD") ?: System.getenv("ASR_RELEASE_KEY_PASSWORD")
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Release signing is partially configured. Set all ASR_RELEASE_* signing values or none.")
}
if (releaseSigningConfigured && !rootProject.file(releaseSigningValues[0]!!).isFile) {
    throw GradleException("ASR_RELEASE_STORE_FILE does not point to an existing keystore file.")
}

android {
    namespace = "org.asr.runtime"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.asr.runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                if (ros2AndroidPrefix.isNotEmpty()) {
                    arguments("-DROS2_ANDROID_PREFIX=$ros2AndroidPrefix")
                }
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
    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(releaseSigningValues[0]!!)
                storePassword = releaseSigningValues[1]
                keyAlias = releaseSigningValues[2]
                keyPassword = releaseSigningValues[3]
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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
