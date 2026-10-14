// Generates CarLink's baseline and startup profiles and measures them with Macrobenchmark
// (docs/LOW_END_PERFORMANCE.md "Round 2"). Runs against the release build on one of CarLink's
// emulators; set ANDROID_SERIAL so Gradle uses only that device:
//
//   ANDROID_SERIAL=emulator-5562 ./gradlew :app:generateReleaseBaselineProfile      (API 30 needs `adb root`)
//   ANDROID_SERIAL=emulator-5562 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.carlink.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // BaselineProfileRule needs API 28+ (root below 33); Macrobenchmark needs 23+.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // CarLink's measuring devices are emulators (no low-end phone is attached to this PC).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        missingDimensionStrategy("distribution", "github")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
