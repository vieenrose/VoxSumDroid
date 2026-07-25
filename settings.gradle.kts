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
// that still expects sherpa-onnx and the retired SenseVoice/Qwen3 backends. Re-add the include
// to build it again. Deleting app/ instead is NOT worth it: this branch merges `main`
// regularly, so a deletion turns every future merge into deleted-vs-modified conflicts across
// the whole module.
//
// Its C++ sources under app/src/main/cpp ARE still used — the desktop CMake compiles
// mosslite/ (and llm_jni.cpp, which only lives here now that main dropped llama.cpp) from
// there. That makes them a SYNC HAZARD: a fix landing on main reaches the desktop only when
// someone merges. It has bitten once — main's per-model .xnncache fix sat unmerged and the
// desktop ran with its XNNPACK weight cache silently disabled. After merging main, check:
//     git diff origin/main -- app/src/main/cpp/mosslite/
// Anything there beyond a deliberate desktop-vs-Android difference is drift to resolve.
include(":shared")
include(":desktop")
