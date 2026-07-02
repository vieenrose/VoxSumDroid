import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.foundation)
            implementation(compose.runtime)
        }
    }
}

compose.desktop {
    application {
        mainClass = "studio.voxsum.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "VoxSum"
            packageVersion = "0.1.0"
            description = "Offline audio transcription and summarization"
            linux {
                packageName = "voxsum"
            }
        }
    }
}
