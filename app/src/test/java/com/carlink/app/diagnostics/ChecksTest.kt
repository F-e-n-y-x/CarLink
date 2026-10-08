package com.carlink.app.diagnostics

import android.app.Application
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ChecksTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test fun sourceBuildWithoutAuthenticationFilesFailsCarPlayCheck() {
        val result = Checks.carPlayAuthentication(context, bundled = false)
        assertEquals(CheckStatus.FAIL, result.status)
        assertEquals(CarPlayAuthSource.MISSING, result.authSource)
        // Nothing in Settings can supply the files, so there is no Fix button.
        assertEquals(null, result.fix)
    }

    @Test fun filesInstalledByAnEarlierBuildStillAuthenticate() {
        val dir = File(context.noBackupFilesDir, Checks.AUTH_DIRECTORY).apply { mkdirs() }
        Checks.AUTH_FILES.forEach { File(dir, it).writeText("x") }
        val result = Checks.carPlayAuthentication(context, bundled = false)
        assertEquals(CheckStatus.PASS, result.status)
        assertEquals(CarPlayAuthSource.INSTALLED, result.authSource)
    }

    @Test fun externalAuthenticationDoesNotNeedTheFiles() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        val result = Checks.carPlayAuthentication(context, bundled = false)
        assertEquals(CheckStatus.INFO, result.status)
        assertEquals(CarPlayAuthSource.EXTERNAL, result.authSource)
    }

    @Test fun problemsSortFirstAndKeepTheirOrder() {
        val results = listOf(
            CheckResult(CheckId.BLUETOOTH, CheckStatus.PASS),
            CheckResult(CheckId.OVERLAY, CheckStatus.WARN),
            CheckResult(CheckId.WIFI_DIRECT, CheckStatus.INFO),
            CheckResult(CheckId.CARPLAY_AUTHENTICATION, CheckStatus.FAIL),
            CheckResult(CheckId.BATTERY_OPTIMIZATION, CheckStatus.WARN),
        )
        assertEquals(
            listOf(CheckId.CARPLAY_AUTHENTICATION, CheckId.OVERLAY, CheckId.BATTERY_OPTIMIZATION, CheckId.WIFI_DIRECT, CheckId.BLUETOOTH),
            Checks.sorted(results).map { it.id },
        )
        assertEquals(3, Checks.problemCount(results))
    }

    @Test fun androidSoftwareCodecsAreNotHardware() {
        assertTrue(Decoders.isSoftwareCodecName("OMX.google.h264.decoder"))
        assertTrue(Decoders.isSoftwareCodecName("c2.android.avc.decoder"))
        assertFalse(Decoders.isSoftwareCodecName("OMX.qcom.video.decoder.avc"))
        assertFalse(Decoders.isSoftwareCodecName("c2.qti.avc.decoder"))
    }

    @Test fun licenseTextReflowsIntoParagraphs() {
        val text = "GNU AFFERO\n  Version 3\n\nPreamble line one\nline two\r\n\r\n\n"
        assertEquals(listOf("GNU AFFERO Version 3", "Preamble line one line two"), AppInfo.paragraphs(text))
    }
}
