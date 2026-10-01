// DiPlay Home: a small launcher with the live CarPlay map and any Android widgets.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.home"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.home"
        minSdk = 30 // SurfaceView.getHostToken and setChildSurfacePackage
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
