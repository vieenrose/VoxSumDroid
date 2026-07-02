import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
    jvm()
    // Kept at 17 to match app/build.gradle.kts's Android compileOptions (compileDebugJavaWithJavac
    // still targets 17 there); the jvm() desktop target uses the toolchain default (21) instead.
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.material3)
            implementation(compose.foundation)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.commons.compress) // ModelManager: .tar.bz2 model extraction
        }
        androidMain.dependencies {
            // WindowCompat, for the Android status-bar actual.
            implementation(libs.androidx.core.ktx)
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

android {
    namespace = "studio.voxsum.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}
