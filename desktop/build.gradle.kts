import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Single source of truth for the app version — drives both the packaged .deb/AppImage version and
// the AppInfo.VERSION constant the About screen shows (generated below, so they never drift).
val appVersion = "0.7.0"

val generateVersion by tasks.registering {
    // Capture into task-local vals (not script-level references) so the configuration cache can
    // serialize the task action.
    val version = appVersion
    val outFile = layout.buildDirectory.file("generated/version/studio/voxsum/desktop/AppInfo.kt")
    inputs.property("version", version)
    outputs.file(outFile)
    doLast {
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "package studio.voxsum.desktop\n\n" +
                    "/** Generated from build.gradle.kts `appVersion` — do not edit by hand. */\n" +
                    "object AppInfo { const val VERSION = \"$version\" }\n",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/version"))
        }
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.foundation)
            implementation(compose.runtime)
            // Pure-JVM PDF writer (Apache-2.0) for PDF export — Android uses the platform's
            // android.graphics.pdf.PdfDocument, which has no desktop equivalent.
            implementation("org.apache.pdfbox:pdfbox:3.0.3")
            // Same version :app uses — a pure-JVM library (not Android-specific), for YouTube
            // audio-source resolution.
            implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
        }
    }
}

tasks.named("compileKotlinJvm") { dependsOn(generateVersion) }

compose.desktop {
    application {
        mainClass = "studio.voxsum.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "VoxSum"
            packageVersion = appVersion
            description = "Offline audio transcription and summarization"
            vendor = "VoxSum"
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
                debMaintainer = "louis_liu@pesi.com.tw"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                // Install a .desktop launcher (app-menu entry) + icon, so the app is reachable from
                // the KDE/GNOME menu after `apt install`, not just via /opt/voxsum/bin/VoxSum.
                shortcut = true
                iconFile.set(project.layout.projectDirectory.file("packaging/voxsum.png"))
            }
        }
    }
}

