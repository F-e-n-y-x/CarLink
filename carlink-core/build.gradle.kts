// CarLink's connection backend as a library: phone detection, the session layer, both engine
// adapters, the unified settings and the session log. Both engines come with it (api), so a host
// app (CarLink, or a launcher) depends on this module alone. See docs/INTEGRATION.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.carlink.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 25
        // The Android Auto engine library ships two distribution flavors; CarLink uses github.
        missingDimensionStrategy("distribution", "github")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":carplay-common"))
    api(project(":carplay-shared"))
    api(project(":androidauto"))

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
