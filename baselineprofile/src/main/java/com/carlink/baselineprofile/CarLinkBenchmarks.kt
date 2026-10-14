package com.carlink.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the profiles move the numbers: every journey runs with no AOT code ([CompilationMode.None],
 * a fresh sideload before background dexopt) and with the shipped profile required
 * ([BaselineProfileMode.Require] fails when the APK has none). Medians are what count.
 *
 *   ANDROID_SERIAL=emulator-5562 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class CarLinkBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun startupNone() = startup(CompilationMode.None())
    @Test fun startupProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun settingsNone() = settings(CompilationMode.None())
    @Test fun settingsProfile() = settings(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitForHome()
    }

    /** Home → Settings → back, then Settings with its categories and scrolling. */
    private fun settings(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        // No startup mode: a cold start would kill the app between setupBlock and the journey.
        startupMode = null,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForHome()
        },
    ) {
        settingsAndBack()
        browseSettings()
    }
}
