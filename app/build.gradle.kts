plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional local-only CarPlay accessory identity (see vendor/diplay docs). Ordinary builds
// contain no credentials; CarPlay then reports that authentication assets are missing.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

// About shows the CarPlay engine's own version. DiPlay keeps it in its (unbuilt) :mobile module,
// so read it from there; a subtree pull then updates About without a CarLink change.
val diPlayVersionName: String = Regex("""versionName\s*=\s*"([^"]+)"""")
    .find(rootProject.file("vendor/diplay/mobile/build.gradle.kts").readText())
    ?.groupValues?.get(1) ?: "unknown"

/**
 * Copies the GPL-3.0 and AGPL-3.0 texts from the vendored engines into the APK's assets, so
 * About > Open-source licenses can show the full terms CarLink is distributed under.
 */
abstract class CarLinkLicenseAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val agplText: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val gplText: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("carlink-licenses")
        dir.deleteRecursively()
        dir.mkdirs()
        agplText.get().asFile.copyTo(dir.resolve("AGPL-3.0.txt"))
        gplText.get().asFile.copyTo(dir.resolve("GPL-3.0.txt"))
    }
}

androidComponents {
    onVariants { variant ->
        val licenses = tasks.register<CarLinkLicenseAssets>("${variant.name}CarLinkLicenseAssets") {
            agplText.set(rootProject.file("vendor/diauto/LICENSE"))
            gplText.set(rootProject.file("vendor/diplay/LICENSE"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licenses, CarLinkLicenseAssets::outputDir)
    }
}

android {
    namespace = "com.carlink.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = providers.gradleProperty("carlink.applicationId").get()
        minSdk = 25
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DIPLAY_VERSION_NAME", "\"$diPlayVersionName\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Android Auto engine library ships two distribution flavors; CarLink uses github.
        missingDimensionStrategy("distribution", "github")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.directories.add(it.path) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = providers.gradleProperty("carlink.debugApplicationIdSuffix").get()
            versionNameSuffix = "-hud-test"
        }
        release {
            // Both engines rely on reflection into framework and BYD classes; DiPlay ships
            // unshrunk for the same reason.
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // CarLink ships in English only, including the engines' own screens (they carry 20
    // translations). Remove this filter and extend xml/locales_config.xml to bring them back.
    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/license.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt",
                "META-INF/notice.txt", "META-INF/ASL2.0", "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        disable += "PackagedPrivateKey"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Screenshot gallery (docs/screenshots): `./gradlew :app:recordScreenshots` renders every
// registered screen state with Robolectric and rewrites the PNGs. Ordinary test runs skip it.
val recordingScreenshots = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "recordScreenshots" } ||
    providers.gradleProperty("carlink.screenshots").orNull == "true"
val repoRootPath: String = rootDir.path
tasks.withType<Test>().configureEach {
    systemProperty("carlink.repoRoot", repoRootPath)
    systemProperty("carlink.screenshots", recordingScreenshots.toString())
    if (recordingScreenshots && name == "testDebugUnitTest") {
        filter.includeTestsMatching("com.carlink.app.gallery.*")
        outputs.upToDateWhen { false }
        maxHeapSize = "3g"
    }
}
tasks.register("recordScreenshots") {
    group = "verification"
    description = "Renders the CarLink UI gallery into docs/screenshots."
    dependsOn("testDebugUnitTest")
}

dependencies {
    implementation(project(":carplay-common"))
    implementation(project(":carplay-shared"))
    implementation(project(":androidauto"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    debugImplementation(libs.androidx.compose.ui.tooling)
    // QR codes for source and project links in About (same version the Android Auto engine uses).
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.17")
}
