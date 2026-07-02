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
include(":app")
include(":shared")
include(":desktop")
