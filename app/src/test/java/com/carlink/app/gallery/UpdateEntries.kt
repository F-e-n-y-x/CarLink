package com.carlink.app.gallery

import androidx.compose.runtime.Composable
import com.carlink.app.ui.about.AboutContent
import com.carlink.app.ui.about.previewFacts
import com.carlink.app.ui.about.previewUpdates
import com.carlink.app.ui.home.HomeContent
import com.carlink.app.ui.update.UpdateDialog
import com.carlink.app.ui.update.UpdateDialogActions
import com.carlink.app.update.AppVersion
import com.carlink.app.update.ReleaseInfo
import com.carlink.app.update.ReleaseRepo
import com.carlink.app.update.UpdateError
import com.carlink.app.update.UpdateState

/** The update dialog over Home, on the tablet, a car screen, a ⅓ split car window and a phone. */
private val updateConfigs = setOf("tablet", "car-landscape", "car-split-third", "phone-portrait")

private object GalleryUpdateActions : UpdateDialogActions

private val galleryRelease = AppVersion(1, 1, 0).let { version ->
    ReleaseInfo("v1.1.0", version, "CarLink 1.1.0",
        """
        ## What's new

        - Wireless CarPlay connects faster after the car wakes up.
        - Android Auto keeps its sound when a call ends.
        - **Settings › About** can check for updates.

        ## Fixes

        - The picture no longer stays black on some 1024×600 head units.
        - Switching phones over USB asks only once.
        """.trimIndent(),
        "${ReleaseRepo.DOWNLOAD_PREFIX}v1.1.0/${ReleaseRepo.apkName(version)}", 48_234_567L,
        "${ReleaseRepo.DOWNLOAD_PREFIX}v1.1.0/${ReleaseRepo.checksumName(version)}")
}

internal fun updateEntries(): List<GalleryEntry> = with(HomeFakes) {
    fun entry(name: String, state: UpdateState) = GalleryEntry("update", name, configs = updateConfigs) {
        OverHome { UpdateDialog(state, "1.0.0", GalleryUpdateActions) }
    }
    listOf(
        entry("available", UpdateState.Available(galleryRelease)),
        entry("downloading", UpdateState.Downloading(galleryRelease, 19_400_000L, galleryRelease.apkSize)),
        entry("verify-failed", UpdateState.Failed(galleryRelease, UpdateError.WRONG_SIGNER)),
        entry("permission", UpdateState.NeedsPermission(galleryRelease)),
        // About › Updates after "Check for updates" found nothing newer.
        GalleryEntry("about", "updates-up-to-date", configs = updateConfigs) {
            AboutContent(previewFacts, onBack = {}, updates = previewUpdates.copy(state = UpdateState.UpToDate,
                lastCheck = previewUpdates.now - 20_000L))
        },
    )
}

@Composable
private fun OverHome(content: @Composable () -> Unit) = with(HomeFakes) {
    HomeContent(ui(phones = listOf(iphone, pixel)), actions)
    content()
}
