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
            // Native libs (llama.cpp/ggml, sherpa-onnx+onnxruntime, the voxsum-llm JNI bridge) —
            // see desktop/scripts/{build-native,flatten-native-libs}.sh. Files here are copied
            // into the packaged app image and exposed at runtime via the
            // `compose.application.resources.dir` system property (both dev :desktop:run and the
            // installed .deb/AppImage); studio.voxsum.desktop.NativeLibs reads that property.
            // Each .so's RPATH is rewritten to $ORIGIN by flatten-native-libs.sh so the whole set
            // stays loadable regardless of where jpackage/dpkg ultimately installs it.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            linux {
                packageName = "voxsum"
            }
        }
    }
}
