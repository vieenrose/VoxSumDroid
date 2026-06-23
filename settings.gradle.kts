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
    // F-Droid note: FAIL_ON_PROJECT_REPOS keeps all artifacts declared here only.
    // For the reproducible F-Droid build, every native dependency is compiled from
    // source (see app/src/main/cpp/CMakeLists.txt) — these repos serve AndroidX/Compose only.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VoxSumDroid"
include(":app")
