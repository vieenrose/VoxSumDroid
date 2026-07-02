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
        // Points java.library.path at desktop/scripts/build-native.sh's output — libvoxsum-llm.so
        // (+ llama/ggml), libsherpa-onnx-jni.so (+ its bundled onnxruntime). Run that script once
        // before `:desktop:run`/packaging, or ASR/diarization/summarization all fail to load.
        jvmArgs += listOf(
            "-Djava.library.path=" +
                "${rootProject.projectDir}/desktop/build-native:" +
                "${rootProject.projectDir}/desktop/build-native/lib:" +
                "${rootProject.projectDir}/desktop/build-native/bin:" +
                "${rootProject.projectDir}/desktop/build-native/_deps/onnxruntime-src/lib",
        )
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
