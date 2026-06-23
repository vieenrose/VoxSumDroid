plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "studio.voxsum"
    compileSdk = 35

    // Pin the NDK so F-Droid's build server uses the same toolchain we test with.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "studio.voxsum"
        minSdk = 26          // MediaCodec PCM-float output + reasonable native perf
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64 is the only ABI worth shipping for on-device LLM perf.
            // Add "armeabi-v7a" only if you must support old devices (much slower).
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Build sherpa-onnx (+onnxruntime) and llama.cpp from source.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // sherpa-onnx ships its Kotlin API under com.k2fsa.sherpa.onnx. For the F-Droid
    // source build we add those .kt files as a source set pointing at the submodule
    // (see native/sherpa-onnx), so no prebuilt AAR is committed.
    sourceSets["main"].java.srcDirs(
        "../native/sherpa-onnx/sherpa-onnx/kotlin-api",
    )

    packaging {
        // c++_shared is provided once; avoid duplicate libc++_shared.so clashes.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
}
