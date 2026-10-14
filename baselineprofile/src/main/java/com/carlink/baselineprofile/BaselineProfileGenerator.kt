package com.carlink.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records CarLink's baseline profile (the code ART compiles at install) and its startup profile
 * (the classes R8 lays out first in the dex). `./gradlew :app:generateReleaseBaselineProfile`
 * writes them to app/src/release/generated/baselineProfiles/; they add to the hand-written
 * wildcard profiles of :app and :carlink-core, which stay.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    /** Cold start to Home: the only journey in the startup profile (dex layout). */
    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        waitForHome()
    }

    /** What the driver does next without a phone: Settings and its categories, Diagnostics. */
    @Test
    fun screens() = rule.collect(packageName = PACKAGE, includeInStartupProfile = false) {
        pressHome()
        startActivityAndWait()
        waitForHome()
        browseSettings()
        openDiagnostics()
    }
}
