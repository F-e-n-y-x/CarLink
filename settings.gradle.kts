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

// The shipped APK: CarLink shell, phone detection and engine dispatch.
include(":app")

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
