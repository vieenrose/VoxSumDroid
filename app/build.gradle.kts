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
        versionCode = 23
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64 is the only ABI worth shipping for on-device LLM perf. Override with
            // -PvoxsumAbi=x86_64 to build for an emulator (provide a matching ORT via
            // SHERPA_ONNXRUNTIME_LIB_DIR). See RELEASING.md / the emulator test in SPIKE.md.
            abiFilters += ((project.findProperty("voxsumAbi") as String?) ?: "arm64-v8a")
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

    // Release signing is driven by env vars so CI can inject a keystore from secrets and
    // local/debug builds still work without any. See RELEASING.md.
    val keystorePath = System.getenv("VOXSUM_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("VOXSUM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VOXSUM_KEY_ALIAS")
                keyPassword = System.getenv("VOXSUM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

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
    // Full Material icon set (Pause/Stop/Mic/Tune/GraphicEq/Volume…). R8 tree-shakes
    // unused icons, so release-APK impact is negligible. BOM supplies the version.
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.kotlinx.coroutines.android)
    // YouTube source extraction (GPL-3.0, via JitPack). Pulls nanojson/jsoup/rhino transitively.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    implementation(libs.commons.compress) // tar.bz2 model extraction (Apache-2.0)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
