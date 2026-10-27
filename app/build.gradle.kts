plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
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

/** `major.minor.patch` → major * 10000 + minor * 100 + patch (see defaultConfig). */
fun carLinkVersionCode(name: String): Int {
    val parts = Regex("""(\d{1,4})\.(\d{1,2})\.(\d{1,2})""").matchEntire(name)?.destructured?.toList()
        ?: error("versionName must be major.minor.patch with minor and patch below 100: $name")
    return parts[0].toInt() * 10_000 + parts[1].toInt() * 100 + parts[2].toInt()
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
        // Releases: change versionName only. versionCode = major * 10000 + minor * 100 + patch
        // (1.0.0 → 10000, 1.2.3 → 10203), so minor and patch stay below 100. The in-app updater
        // (update/AppVersion.kt) and scripts/release.sh rely on it; docs/RELEASING.md.
        versionName = "1.0.7"
        versionCode = carLinkVersionCode(versionName!!)
        buildConfigField("String", "DIPLAY_VERSION_NAME", "\"$diPlayVersionName\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Android Auto engine library ships two distribution flavors; CarLink uses github.
        missingDimensionStrategy("distribution", "github")
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

    // Car stereos and tablets are ARM: arm64-v8a, and armeabi-v7a for the many 32-bit head units.
    // x86_64 is only for the emulator: debug builds carry it, release builds only with
    // -Pcarlink.emulatorAbi=true (measuring a release build on the emulator). It is 3.3 MB of
    // native code no car stereo runs (docs/LOW_END_PERFORMANCE.md).
    val armAbis = listOf("arm64-v8a", "armeabi-v7a")
    val releaseEmulatorAbi = providers.gradleProperty("carlink.emulatorAbi").orNull == "true"
    buildTypes {
        debug {
            applicationIdSuffix = providers.gradleProperty("carlink.debugApplicationIdSuffix").get()
            versionNameSuffix = "-hud-test"
            ndk { abiFilters += armAbis + "x86_64" }
        }
        release {
            ndk { abiFilters += if (releaseEmulatorAbi) armAbis + "x86_64" else armAbis }
            // R8 shrinks and optimizes code and resources, without renaming (-dontobfuscate):
            // app rules in src/main/keepRules/carlink.keep, engine rules in the engines'
            // consumer files (design/RESEARCH_2026-10_R8_PLATFORM.md, phase 1).
            optimization {
                enable = true
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
                // Bouncy Castle's Picnic (post-quantum signature) tables, 1.2 MB: neither engine
                // uses Picnic, and only Picnic's own classes read them.
                "org/bouncycastle/pqc/crypto/picnic/*.properties",
                // Its German certificate-path messages (CarLink ships English only).
                "org/bouncycastle/x509/CertPathReviewerMessages_de.properties",
                // kotlinx-coroutines' debug-agent probes; only read by an attached debug agent.
                "DebugProbesKt.bin",
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

// Stability: see compose-stability.conf.
// `-Pcarlink.composeReports=true`: the Compose compiler's stability reports and metrics in
// app/build/compose_compiler/ (which composables skip, which classes are unstable). A debugging
// aid only; ordinary builds do not write them.
composeCompiler {
    // Immutable value classes from :carlink-core and read-only collections count as stable.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    if (providers.gradleProperty("carlink.composeReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

// Baseline and startup profiles recorded by :baselineprofile on a CarLink emulator
// (docs/LOW_END_PERFORMANCE.md "Round 2"). The generated files are committed under
// src/release/generated/baselineProfiles/ and merged with the hand-written src/main/baseline-prof.txt
// and :carlink-core's; a normal build never runs the generator.
baselineProfile {
    automaticGenerationDuringBuild = false
    // The startup profile lets R8 put start-up classes first in classes.dex (dex layout).
    dexLayoutOptimization = true
}

// Screenshot gallery (docs/screenshots): `./gradlew :app:recordScreenshots` renders every
// registered screen state with Robolectric and rewrites the PNGs. Ordinary test runs skip it.
val recordingScreenshots = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "recordScreenshots" } ||
    providers.gradleProperty("carlink.screenshots").orNull == "true"
val repoRootPath: String = rootDir.path
tasks.withType<Test>().configureEach {
    systemProperty("carlink.repoRoot", repoRootPath)
    systemProperty("carlink.screenshots", recordingScreenshots.toString())
    providers.gradleProperty("carlink.gallery.areas").orNull?.let { systemProperty("carlink.gallery.areas", it) }
    // `-Pcarlink.only=<regex>` renders only the images whose file name matches (fast iteration).
    systemProperty("carlink.screenshots.only", providers.gradleProperty("carlink.only").orNull ?: "")
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
    implementation(project(":carlink-core"))
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
    // Hands the APK's baseline profile to ART on a sideloaded install (no Play Store to do it);
    // declared explicitly rather than relying on Compose pulling it in.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
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
