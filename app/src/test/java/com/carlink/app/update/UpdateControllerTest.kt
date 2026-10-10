package com.carlink.app.update

import android.app.Application
import com.carlink.app.ui.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * The updater's flow with fakes for GitHub, the verifier's inspector and the installer. Every
 * fake answers at once and the dispatcher is Unconfined, so each call runs to its end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class UpdateControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private val hour = 60 * 60 * 1000L
    private var now = 1_760_000_000_000L
    private val apkBytes = "the new CarLink"
    private var release = UpdatePolicyTest.release("v1.1.0")
    private var outcome: CheckOutcome = CheckOutcome.Newer(release)
    private var checks = 0
    private var facts: ApkFacts? = ApkFacts(Updates.RELEASE_PACKAGE, 10100L, setOf(ReleaseKey.normalized))
    private var checksum: String? = null
    private var downloadFails = false
    private val installer = FakeInstaller()
    private val prefs = MemoryPrefs()
    private lateinit var downloadDir: File

    private val http = object : UpdateHttp {
        override fun getText(url: String, accept: String?): HttpText =
            HttpText(200, checksum ?: JavaSha256.sha256Hex(apkBytes.toByteArray()) + "  CarLink-1.1.0.apk")

        override fun download(url: String, destination: File, isCancelled: () -> Boolean, onProgress: (Long, Long?) -> Unit) {
            if (downloadFails) throw IOException("connection reset")
            destination.parentFile!!.mkdirs()
            destination.writeText(apkBytes)
            onProgress(apkBytes.length.toLong(), apkBytes.length.toLong())
        }
    }

    private fun controller(supported: Boolean = true, prefs: UpdatePrefs = this.prefs): UpdateController {
        downloadDir = File(temp.root, "updates")
        return UpdateController(
            scope = CoroutineScope(Dispatchers.Unconfined), io = Dispatchers.Unconfined,
            source = { checks++; outcome }, http = http,
            verifier = UpdateVerifier(JavaSha256, { facts }, Updates.RELEASE_PACKAGE, 10000L),
            installer = installer, prefs = prefs, current = AppVersion(1, 0, 0), supported = supported,
            downloadDir = downloadDir, clock = { now },
        )
    }

    @Test fun noAutomaticCheckDuringASession() {
        val c = controller()
        assertFalse(c.maybeAutoCheck(sessionQuiet = false))
        assertEquals(0, checks)
        assertEquals(UpdateState.Idle, c.state.value)
        assertTrue(c.maybeAutoCheck(sessionQuiet = true))
        assertEquals(UpdateState.Available(release), c.state.value)
    }

    @Test fun automaticCheckWaitsSixHoursAfterLater() {
        val c = controller()
        c.maybeAutoCheck(true)
        c.later()
        assertEquals(UpdateState.Idle, c.state.value)
        now += 5 * hour
        assertFalse(c.maybeAutoCheck(true))
        now += 1 * hour
        assertTrue(c.maybeAutoCheck(true))
        assertEquals(2, checks)
        assertEquals(UpdateState.Available(release), c.state.value)
    }

    @Test fun debugBuildsAndTheSwitchStopChecks() {
        assertFalse(controller(supported = false).maybeAutoCheck(true))
        controller(supported = false).checkNow()
        prefs.autoCheckUpdates = false
        assertFalse(controller().maybeAutoCheck(true))
        assertEquals(0, checks)
    }

    @Test fun ignoredVersionIsNotOfferedAgainButANewerOneIs() {
        val c = controller()
        c.maybeAutoCheck(true)
        c.ignore()
        assertEquals("v1.1.0", prefs.ignoredUpdateTag)
        now += 7 * hour
        c.maybeAutoCheck(true)
        assertEquals(UpdateState.Idle, c.state.value)
        release = UpdatePolicyTest.release("v1.2.0")
        outcome = CheckOutcome.Newer(release)
        now += 7 * hour
        c.maybeAutoCheck(true)
        assertEquals(UpdateState.Available(release), c.state.value)
    }

    @Test fun ignoredVersionPersistsInUiPreferences() {
        val app = RuntimeEnvironment.getApplication()
        val c = controller(prefs = UiPreferences(app))
        c.checkNow()
        c.ignore()
        val reopened = UiPreferences(app)
        assertEquals("v1.1.0", reopened.ignoredUpdateTag)
        assertEquals(now, reopened.lastUpdateCheckMillis)
        assertTrue("on by default", reopened.autoCheckUpdates)
        // A fresh start within six hours stays quiet; the ignored tag stays silent after that.
        assertFalse(controller(prefs = reopened).maybeAutoCheck(true))
        now += 7 * hour
        val later = controller(prefs = UiPreferences(app))
        assertTrue(later.maybeAutoCheck(true))
        assertEquals(UpdateState.Idle, later.state.value)
    }

    @Test fun manualCheckAnswers() {
        val c = controller()
        outcome = CheckOutcome.UpToDate
        c.checkNow()
        assertEquals(UpdateState.UpToDate, c.state.value)
        c.dismiss()
        outcome = CheckOutcome.RateLimited
        c.checkNow()
        assertEquals(UpdateState.CheckFailed(rateLimited = true), c.state.value)
        c.dismiss()
        outcome = CheckOutcome.Failed("offline")
        c.checkNow()
        assertEquals(UpdateState.CheckFailed(rateLimited = false), c.state.value)
    }

    @Test fun automaticFailuresStaySilent() {
        outcome = CheckOutcome.RateLimited
        val c = controller()
        c.maybeAutoCheck(true)
        assertEquals(UpdateState.Idle, c.state.value)
    }

    @Test fun downloadVerifyAndInstall() {
        val c = controller()
        c.checkNow()
        c.download()
        assertEquals(UpdateState.Installing(release), c.state.value)
        assertEquals(apkBytes, installer.installed.single())
        assertTrue("the verified file is deleted once the session has it", downloadDir.listFiles().isNullOrEmpty())
        c.onInstallStatus(InstallStatus.WaitingForUser)
        assertEquals(UpdateState.Installing(release), c.state.value)
        c.onInstallStatus(InstallStatus.Aborted)
        assertEquals(UpdateError.INSTALL_CANCELLED, (c.state.value as UpdateState.Failed).error)
    }

    @Test fun askForInstallPermissionThenContinue() {
        installer.allowed = false
        val c = controller()
        c.checkNow()
        c.download()
        assertEquals(UpdateState.NeedsPermission(release), c.state.value)
        c.openInstallPermission()
        assertTrue(installer.openedSettings)
        // Back on Home without allowing it: still waiting.
        c.maybeAutoCheck(true)
        assertEquals(UpdateState.NeedsPermission(release), c.state.value)
        installer.allowed = true
        c.maybeAutoCheck(true)
        assertEquals(UpdateState.Installing(release), c.state.value)
        assertEquals(1, installer.installed.size)
    }

    @Test fun checksumMismatchDeletesTheFile() {
        checksum = "0".repeat(64)
        assertFailure(UpdateError.CHECKSUM_MISMATCH)
    }

    @Test fun missingChecksumFails() {
        checksum = "Not Found"
        assertFailure(UpdateError.CHECKSUM_MISSING)
    }

    @Test fun wrongSignerDeletesTheFile() {
        facts = facts!!.copy(signerSha256 = setOf("f".repeat(64)))
        assertFailure(UpdateError.WRONG_SIGNER)
    }

    @Test fun olderApkDeletesTheFile() {
        facts = facts!!.copy(versionCode = 10000L)
        assertFailure(UpdateError.NOT_NEWER)
    }

    @Test fun networkFailureCanBeRetried() {
        downloadFails = true
        val c = assertFailure(UpdateError.DOWNLOAD)
        assertTrue(UpdateError.DOWNLOAD.canRetry)
        downloadFails = false
        c.download()
        assertEquals(UpdateState.Installing(release), c.state.value)
    }

    @Test fun staleDownloadsAreRemovedAtStart() {
        val stale = File(temp.root, "updates/CarLink-0.9.0.apk").apply { parentFile!!.mkdirs(); writeText("old") }
        controller()
        assertFalse(stale.exists())
    }

    private fun assertFailure(error: UpdateError): UpdateController {
        val c = controller()
        c.checkNow()
        c.download()
        val failed = c.state.value as UpdateState.Failed
        assertEquals(error, failed.error)
        assertTrue("no APK left behind", downloadDir.listFiles().isNullOrEmpty())
        assertTrue(installer.installed.isEmpty())
        return c
    }

    private class MemoryPrefs : UpdatePrefs {
        override var autoCheckUpdates = true
        override var ignoredUpdateTag: String? = null
        override var lastUpdateCheckMillis = 0L
    }

    private class FakeInstaller : UpdateInstaller {
        var allowed = true
        var openedSettings = false
        val installed = mutableListOf<String>()
        override fun canInstall() = allowed
        override fun openInstallPermissionSettings() { openedSettings = true }
        override fun install(file: File) { installed += file.readText() }
    }
}
