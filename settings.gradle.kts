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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu (Android Auto engine root helper) is only published on JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}

rootProject.name = "CarLink"

// The shipped APK: CarLink's screens, the floating switcher and the app wiring.
include(":app")

// Generates the app's baseline and startup profiles and benchmarks them (Macrobenchmark,
// docs/LOW_END_PERFORMANCE.md "Round 2"). Runs on a CarLink emulator; never part of the APK.
include(":baselineprofile")

// The connection backend as a reusable library (detection, sessions, engine adapters, settings).
include(":carlink-core")

// CarPlay engine, imported from DiPlay (git subtree at vendor/diplay).
include(":carplay-shared")
project(":carplay-shared").projectDir = file("vendor/diplay/shared")
include(":carplay-common")
project(":carplay-common").projectDir = file("vendor/diplay/common")

// Android Auto engine, imported from DiAuto (git subtree at vendor/diauto).
include(":androidauto")
project(":androidauto").projectDir = file("vendor/diauto/app")
include(":androidauto-contract")
project(":androidauto-contract").projectDir = file("vendor/diauto/contract")
