package com.carlink.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Where the updater is; the update dialog and About's update row are drawn from it. */
sealed interface UpdateState {
    data object Idle : UpdateState
    /** A check the driver asked for (automatic checks run silently). */
    data object Checking : UpdateState
    /** The answer to a check the driver asked for. */
    data object UpToDate : UpdateState
    data class CheckFailed(val rateLimited: Boolean) : UpdateState

    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long?) : UpdateState
    data class Verifying(val release: ReleaseInfo) : UpdateState
    /** Android must allow CarLink to install apps first ("Install unknown apps"). */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val release: ReleaseInfo, val error: UpdateError, val detail: String? = null) : UpdateState
}

/** The update dialog is open in these states. */
val UpdateState.showsDialog: Boolean
    get() = this is UpdateState.Available || this is UpdateState.Downloading || this is UpdateState.Verifying ||
        this is UpdateState.NeedsPermission || this is UpdateState.Installing || this is UpdateState.Failed

/** Why an update did not install. The downloaded file is deleted in every case. */
enum class UpdateError(val canRetry: Boolean) {
    DOWNLOAD(true),
    CHECKSUM_MISSING(true),
    CHECKSUM_MISMATCH(false),
    UNREADABLE(false),
    WRONG_PACKAGE(false),
    NOT_NEWER(false),
    WRONG_SIGNER(false),
    INSTALL(true),
    INSTALL_CANCELLED(true),
}

private fun VerifyFailure.toError(): UpdateError = when (this) {
    VerifyFailure.CHECKSUM_MISMATCH -> UpdateError.CHECKSUM_MISMATCH
    VerifyFailure.UNREADABLE -> UpdateError.UNREADABLE
    VerifyFailure.WRONG_PACKAGE -> UpdateError.WRONG_PACKAGE
    VerifyFailure.NOT_NEWER -> UpdateError.NOT_NEWER
    VerifyFailure.WRONG_SIGNER -> UpdateError.WRONG_SIGNER
}

/**
 * The updater: check GitHub, offer a newer release, download it into [downloadDir], verify it
 * ([UpdateVerifier]) and hand it to [installer]. UI-free; call it on the main thread. Every
 * dependency is an interface, so a launcher can reuse it and tests can drive it.
 *
 * Automatic checks ([maybeAutoCheck]) run at most every 6 hours, only when [supported] (the
 * official release build), only with "Automatically check for updates" on, and only while no
 * projection session runs (the caller passes [UpdatePolicy.sessionQuiet]). "Ignore this
 * version" silences that tag for good; "Later" closes the dialog until the next check.
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val source: ReleaseSource,
    private val http: UpdateHttp,
    private val verifier: UpdateVerifier,
    private val installer: UpdateInstaller,
    private val prefs: UpdatePrefs,
    val current: AppVersion,
    /** Release build signed with the release key: the only build that checks and installs. */
    val supported: Boolean,
    private val downloadDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _autoCheck = MutableStateFlow(prefs.autoCheckUpdates)
    /** "Automatically check for updates". */
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    private val _lastCheck = MutableStateFlow(prefs.lastUpdateCheckMillis)
    /** Wall-clock time of the last check, 0 for never. */
    val lastCheck: StateFlow<Long> = _lastCheck.asStateFlow()

    private var job: Job? = null
    private var verifiedFile: File? = null

    init {
        // An APK left from an earlier run (the update installed, or the app was closed mid-way).
        downloadDir.listFiles()?.forEach { it.delete() }
    }

    fun setAutoCheck(enabled: Boolean) {
        prefs.autoCheckUpdates = enabled
        _autoCheck.value = enabled
    }

    /**
     * Home is in front: check if one is due and [sessionQuiet]. Also continues an install that
     * waited for "Install unknown apps". Returns true when a check started.
     */
    fun maybeAutoCheck(sessionQuiet: Boolean): Boolean {
        resumeAfterPermission()
        if (!sessionQuiet || job?.isActive == true || _state.value != UpdateState.Idle) return false
        if (!UpdatePolicy.autoCheckDue(clock(), prefs.lastUpdateCheckMillis, prefs.autoCheckUpdates, supported)) return false
        check(manual = false)
        return true
    }

    /** About › Check for updates. Answers with [UpdateState.UpToDate], [UpdateState.CheckFailed] or the dialog. */
    fun checkNow() {
        if (!supported || job?.isActive == true) return
        if (_state.value.showsDialog) return
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        val now = clock()
        prefs.lastUpdateCheckMillis = now
        _lastCheck.value = now
        if (manual) _state.value = UpdateState.Checking
        job = scope.launch {
            val outcome = withContext(io) { source.latest() }
            log("check (${if (manual) "manual" else "automatic"}): $outcome")
            _state.value = when (outcome) {
                is CheckOutcome.Newer ->
                    if (UpdatePolicy.shouldOffer(outcome.release, current, prefs.ignoredUpdateTag, manual)) UpdateState.Available(outcome.release)
                    else UpdateState.Idle
                CheckOutcome.UpToDate -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                CheckOutcome.RateLimited -> if (manual) UpdateState.CheckFailed(rateLimited = true) else UpdateState.Idle
                is CheckOutcome.Failed -> if (manual) UpdateState.CheckFailed(rateLimited = false) else UpdateState.Idle
            }
        }
    }

    /** "Later": close until the next check window. */
    fun later() {
        if (_state.value is UpdateState.Available) _state.value = UpdateState.Idle
    }

    /** "Ignore this version": never offer this tag again automatically; a newer tag is offered. */
    fun ignore() {
        val available = _state.value as? UpdateState.Available ?: return
        prefs.ignoredUpdateTag = available.release.tag
        _state.value = UpdateState.Idle
    }

    /** Close an answer or a failure. */
    fun dismiss() {
        when (_state.value) {
            UpdateState.UpToDate, is UpdateState.CheckFailed, is UpdateState.Failed -> _state.value = UpdateState.Idle
            // Android's install screen is up (or never came); closing ours leaves the decision there.
            is UpdateState.Installing -> _state.value = UpdateState.Idle
            is UpdateState.Available -> later()
            else -> Unit
        }
    }

    /** "Download & install" (and "Try again" after a failure that allows it). */
    fun download() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release.takeIf { s.error.canRetry }
            else -> null
        } ?: return
        if (job?.isActive == true) return
        val file = File(downloadDir, ReleaseRepo.apkName(release.version))
        _state.value = UpdateState.Downloading(release, 0L, release.apkSize.takeIf { it > 0 })
        job = scope.launch {
            try {
                val checksum = withContext(io) { http.getText(release.checksumUrl) }
                val expected = checksum.takeIf { it.code in 200..299 }?.body?.let(ReleaseParser::parseChecksum)
                if (expected == null) {
                    fail(release, UpdateError.CHECKSUM_MISSING, file)
                    return@launch
                }
                val context = coroutineContext
                var lastPercent = -1L
                withContext(io) {
                    http.download(release.apkUrl, file, isCancelled = { !context.isActive }) { bytes, total ->
                        val percent = if (total != null && total > 0) bytes * 100 / total else bytes / (512 * 1024)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.update { s ->
                                if (s is UpdateState.Downloading && s.release == release) s.copy(bytes = bytes, total = total ?: s.total) else s
                            }
                        }
                    }
                }
                _state.value = UpdateState.Verifying(release)
                val failure = withContext(io) { verifier.verify(file, expected) }
                if (failure != null) {
                    log("verification failed: $failure")
                    fail(release, failure.toError(), file)
                    return@launch
                }
                verifiedFile = file
                installOrAsk(release, file)
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (_: DownloadCancelledException) {
                file.delete()
            } catch (e: IOException) {
                log("download failed: ${e.message}")
                fail(release, UpdateError.DOWNLOAD, file, e.message)
            }
        }
    }

    /** Cancel a download or a pending permission; the offer stays open. */
    fun cancel() {
        val release = when (val s = _state.value) {
            is UpdateState.Downloading -> s.release
            is UpdateState.Verifying -> s.release
            is UpdateState.NeedsPermission -> s.release
            else -> return
        }
        job?.cancel()
        verifiedFile?.delete()
        verifiedFile = null
        _state.value = UpdateState.Available(release)
    }

    /** Opens Android's "Install unknown apps" page; the install continues when Home resumes. */
    fun openInstallPermission() = installer.openInstallPermissionSettings()

    private fun resumeAfterPermission() {
        val waiting = _state.value as? UpdateState.NeedsPermission ?: return
        val file = verifiedFile ?: return
        if (!installer.canInstall()) return
        job = scope.launch { installOrAsk(waiting.release, file) }
    }

    private suspend fun installOrAsk(release: ReleaseInfo, file: File) {
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        _state.value = UpdateState.Installing(release)
        try {
            withContext(io) { installer.install(file) }
            // The session holds its own copy now.
            deleteVerified()
        } catch (e: IOException) {
            log("install failed: ${e.message}")
            fail(release, UpdateError.INSTALL, file, e.message)
        }
    }

    /** The PackageInstaller session's answer (UpdateInstallReceiver). */
    fun onInstallStatus(status: InstallStatus) {
        val installing = _state.value as? UpdateState.Installing ?: run {
            // The dialog was closed while Android's install screen was up: only tidy up.
            if (status !is InstallStatus.WaitingForUser) deleteVerified()
            return
        }
        log("install: $status")
        when (status) {
            InstallStatus.WaitingForUser -> Unit
            // Usually not seen: Android stops CarLink to replace it.
            InstallStatus.Success -> { deleteVerified(); _state.value = UpdateState.Idle }
            InstallStatus.Aborted -> fail(installing.release, UpdateError.INSTALL_CANCELLED, verifiedFile)
            is InstallStatus.Failure -> fail(installing.release, UpdateError.INSTALL, verifiedFile, status.message)
        }
    }

    private fun fail(release: ReleaseInfo, error: UpdateError, file: File?, detail: String? = null) {
        file?.delete()
        deleteVerified()
        _state.value = UpdateState.Failed(release, error, detail)
    }

    private fun deleteVerified() {
        verifiedFile?.delete()
        verifiedFile = null
    }
}
