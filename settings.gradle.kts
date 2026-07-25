pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack: NewPipeExtractor (YouTube source). GPL-3.0 — compatible with this app's
        // GPL-3.0-or-later license. APK-distribution target (not F-Droid reproducible).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "VoxSumDroid"
// :app (the Android app) is NOT built from this branch. It is maintained and released from
// `main`, which completed the LiteRT migration; the copy under app/ here is an old snapshot
// that still expects sherpa-onnx and the retired SenseVoice/Qwen3 backends. Its C++ sources
// under app/src/main/cpp ARE still used — the desktop CMake compiles the shared llm_jni.cpp
// and mosslite/ engines from there. Re-add the include to build it again.
include(":shared")
include(":desktop")
