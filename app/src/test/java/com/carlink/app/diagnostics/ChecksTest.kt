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

    private fun stereo(classification: com.carlink.core.detect.StereoBluetooth) = com.carlink.core.detect.StereoBluetoothReport(
        classification = classification,
        signals = if (classification == com.carlink.core.detect.StereoBluetooth.NONE) emptyList()
            else listOf(com.carlink.core.detect.StereoBluetoothSignal.MODULE_SERIAL_PORT),
        diAutoEvidence = null, properties = emptyMap(), nodes = emptyMap(), daemonPort = com.carlink.core.detect.PortState.CLOSED,
        packages = emptyList(), adapter = com.carlink.core.detect.AdapterFacts(true, true, false), bondedCount = 0, knownPhones = emptyList(),
    )

    @Test fun aBuiltInStereoBluetoothStaysGreen() {
        val result = Checks.stereoBluetooth(stereo(com.carlink.core.detect.StereoBluetooth.NONE), overridden = false)
        assertEquals(CheckId.STEREO_BLUETOOTH, result.id)
        assertEquals(CheckStatus.PASS, result.status)
        assertEquals(0, Checks.problemCount(listOf(result)))
    }

    @Test fun anExternalModuleIsAWarningSoTheSummaryIsNeverAllReady() {
        for (c in com.carlink.core.detect.StereoBluetooth.entries.filter { it.hasSignals }) {
            val result = Checks.stereoBluetooth(stereo(c), overridden = false)
            assertEquals(c.name, CheckStatus.WARN, result.status)
            assertEquals(c.name, null, result.fix)
            // The Diagnostics summary reads problemCount: one warning means not "Everything looks ready".
            assertEquals(1, Checks.problemCount(listOf(result, CheckResult(CheckId.BLUETOOTH, CheckStatus.PASS))))
            assertEquals(CheckId.STEREO_BLUETOOTH, Checks.sorted(listOf(CheckResult(CheckId.BLUETOOTH, CheckStatus.PASS), result)).first().id)
        }
        assertTrue(Checks.stereoBluetooth(stereo(com.carlink.core.detect.StereoBluetooth.FYT_MODULE), overridden = true).overridden)
        assertFalse(Checks.stereoBluetooth(stereo(com.carlink.core.detect.StereoBluetooth.NONE), overridden = true).overridden)
    }
}
