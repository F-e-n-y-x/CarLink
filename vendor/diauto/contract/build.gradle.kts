// CarLink: built with AGP's built-in Kotlin support.
plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = 34
    namespace = "com.andrerinas.openheadunit.contract"

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
