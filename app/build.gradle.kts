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
        versionCode = 126
        versionName = "0.40.2"
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
        debug {
            // -PisolatedTestId installs the instrumented build under its own application id so it
            // coexists with an installed release build. Without it, a debug-signed install of the
            // same id forces an uninstall, taking the user's session library and models with it.
            if (project.hasProperty("isolatedTestId")) applicationIdSuffix = ".androidtest"
            // -PminifyDebug runs the debug build through R8 with the RELEASE keep rules, so
            // JNI-by-name breakage — which cannot reproduce in a normal debug build — is testable
            // on device. This is how the LiteRT-LM SamplerConfig abort was caught; without it the
            // whole instrumented suite passes while every release-build summarize kills the app.
            if (project.hasProperty("minifyDebug")) {
                isMinifyEnabled = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                    "proguard-debug-tests.pro",
                )
            }
        }
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
        // The app dlopen()s libLiteRt.so from jniLibs, so it must be a REAL extracted
        // file rather than a compressed APK entry.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // NOTE: the com.google.ai.edge.litertlm:litertlm-android AAR was removed with Gemma 4.
    // The summarizer now runs on the app's own qwen35lite engine over the libLiteRt.so in
    // jniLibs; the LiteRT-LM runtime cannot supply a pre-packed XNNPACK weight cache and
    // cannot run a hybrid linear-attention graph, and it carried ~90 MB of native libs.
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
    // The platform's org.json is a throw-on-call stub in local unit tests, and the MOSS
    // tokenizer/detokenizer parse the model's vocab.json with it.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Also in the test APK: with a suffixed application id the Compose rules launch
    // ComponentActivity from the TEST package, which must therefore declare it.
    androidTestImplementation("androidx.compose.ui:ui-test-manifest")
}
