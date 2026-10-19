// CarLink: DiAuto's former :app module, built as the Android Auto engine library.
// The APK, its applicationId, signing and shrinking now live in CarLink's :app module.
// Kotlin is compiled by AGP's built-in Kotlin support (no kotlin-android/kapt plugins);
// the Glide and lifecycle annotation processors were unused (no @GlideModule/@OnLifecycleEvent).

plugins {
    alias(libs.plugins.android.library)
}

// Shared with CarLink's :app so engine code that needs the host package (Shizuku user
// service, signature permission names) resolves to the merged APK.
val carlinkApplicationId = providers.gradleProperty("carlink.applicationId").get()
val carlinkDebugSuffix = providers.gradleProperty("carlink.debugApplicationIdSuffix").get()

android {
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    namespace = "com.andrerinas.openheadunit"

    buildFeatures {
        buildConfig = true
        aidl = true // needed for shizuku
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // DiAuto's CHANGELOG/LICENSE live in the subtree root, not the CarLink root.
    val copyRootAssets = tasks.register<Copy>("copyRootAssets") {
        from(file("../CHANGELOG.md"), file("../LICENSE"))
        into("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
    }

    // Scan available locales at configuration time and store as BuildConfig field
    val resDir = file("src/main/res")
    val availableLocales = resDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("values-") &&
        // Filter out non-language qualifiers (night mode, screen size, etc.)
        !file.name.contains("night") &&
        !file.name.contains("land") &&
        !file.name.contains("port") &&
        !file.name.matches(Regex("values-[whsml]\\d+.*")) &&
        !file.name.matches(Regex("values-v\\d+")) &&
        // Check that it contains strings.xml (actual translation)
        file.resolve("strings.xml").exists()
    }?.map { dir ->
        // Extract locale code from directory name (e.g., "values-es" -> "es", "values-pt-rBR" -> "pt-rBR")
        dir.name.removePrefix("values-")
    }?.sorted() ?: emptyList()

    sourceSets {
        getByName("main") {
            assets.directories.add("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
        }
    }

    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
        dependsOn(copyRootAssets)
    }

    tasks.configureEach {
        if (name.contains("lint", ignoreCase = true)) {
            dependsOn(copyRootAssets)
        }
    }

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        consumerProguardFiles("proguard-project.txt")

        // Library BuildConfig has no application/version fields; the engine reads these.
        buildConfigField("String", "APPLICATION_ID", "\"$carlinkApplicationId\"")
        buildConfigField("String", "VERSION_NAME", "\"0.3.12\"")
        buildConfigField("int", "VERSION_CODE", "112")

        // Store available locales in BuildConfig for runtime access
        // This is scanned at build time from values-XX directories
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales.joinToString(",")}\"")

        ndk {
            // Match the CarPlay engine; BYD head units are ARM.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("playstore") {
            dimension = "distribution"
        }
        create("github") {
            dimension = "distribution"
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "APPLICATION_ID", "\"$carlinkApplicationId$carlinkDebugSuffix\"")
        }
    }

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
        targetSdk = 36
    }

    testOptions {
        targetSdk = 36
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// CarLink ships the github distribution only (the playstore flavor never compiled:
// VpnControl.kt lives solely in src/github).
androidComponents {
    beforeVariants(selector().withFlavor("distribution" to "playstore")) { it.enable = false }
}

dependencies {
    // Conscrypt 2.6.1 is 16 KB page aligned; CarLink's minSdk 25 no longer needs 2.5.3.
    implementation("org.conscrypt:conscrypt-android:2.6.1")

    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")

    // KTX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")

    api(project(":androidauto-contract"))

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")

    // DexMaker for runtime subclassing (Hotspot Fix)
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")

    // Glide for image/GIF loading (custom loading screen)
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // ZXing for QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku for root / shell access
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}
