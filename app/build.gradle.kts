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
        versionCode = 106
        versionName = "0.31.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64 is the only ABI worth shipping for on-device LLM perf. Override with
            // -PvoxsumAbi=x86_64 to build for an emulator (provide a matching ORT via
            abiFilters += ((project.findProperty("voxsumAbi") as String?) ?: "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Build the LiteRT MOSS engine (voxsum-mosslite) from source.
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
        // NewPipeExtractor 0.26+ calls Java 10+ APIs (e.g. URLEncoder.encode(String, Charset))
        // that don't exist below API 33 — desugar them for older devices (minSdk 26).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        // c++_shared is provided once; avoid duplicate libc++_shared.so clashes.
        jniLibs.pickFirsts += "**/libc++_shared.so"
        // litertlm-android's engine needs its native libs as REAL extracted files
        // (verified on-device: without extraction, Engine init hangs at 0% CPU).
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // LiteRT-LM in-process engine (official Kotlin API; loads .litertlm bundles).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation(libs.commons.compress) // tar.bz2 model extraction (Apache-2.0)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
