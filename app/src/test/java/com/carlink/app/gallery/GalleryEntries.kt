package com.carlink.app.gallery

import androidx.compose.runtime.Composable
import com.carlink.app.settings.AdvancedSettings
import com.carlink.app.settings.SettingsCategory
import com.carlink.app.ui.settings.EssentialSheet
import com.carlink.app.ui.settings.SettingsContent
import com.carlink.app.ui.settings.SettingsPreviewActions
import com.carlink.app.ui.settings.SheetKey
import com.carlink.app.ui.settings.settingsPreviewUi

/**
 * One screen state in the gallery. [content] is a stateless screen fed with fake state; the
 * harness wraps it in CarLinkTheme for every [GalleryConfig], so do not add a theme here.
 * The PNG lands at docs/screenshots/[area]/[area]_[name]_<config>_<theme>.png.
 *
 * Set [opensWindow] when the state shows a dialog or bottom sheet: those are separate windows
 * that span the whole panel, so the emulated split-screen configs cannot place them and skip
 * the entry.
 */
class GalleryEntry(
    val area: String,
    val name: String,
    val opensWindow: Boolean = false,
    val content: @Composable () -> Unit,
)

/**
 * Every registered screen state. To add a screen, append entries that call its stateless
 * content composable (the one its @Preview functions use) with preview fakes.
 */
val galleryEntries: List<GalleryEntry> = listOf(
    // Root: the rail with Connection on wide screens, the category grid on narrow ones.
    GalleryEntry("settings", "root") {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {})
    },
    GalleryEntry("settings", "connection-paused") {
        SettingsContent(settingsPreviewUi(paused = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.CONNECTION)
    },
    GalleryEntry("settings", "display-schedule") {
        SettingsContent(settingsPreviewUi(schedule = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.DISPLAY)
    },
    GalleryEntry("settings", "display-advanced") {
        SettingsContent(settingsPreviewUi(showAdvanced = true, customPicture = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.DISPLAY)
    },
    GalleryEntry("settings", "sound-advanced-lab") {
        SettingsContent(settingsPreviewUi(showAdvanced = true, lab = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.SOUND)
    },
    GalleryEntry("settings", "general-lab") {
        SettingsContent(settingsPreviewUi(lab = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.GENERAL)
    },
    GalleryEntry("settings", "navigation") {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.NAVIGATION)
    },
    GalleryEntry("settings", "sheet-connection-method", opensWindow = true) {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.CONNECTION,
            initialSheet = SheetKey.of(EssentialSheet.CONNECTION_METHOD))
    },
    GalleryEntry("settings", "sheet-hotspot", opensWindow = true) {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.CONNECTION,
            initialSheet = SheetKey.of(EssentialSheet.SAME_WIFI))
    },
    GalleryEntry("settings", "sheet-picture-custom", opensWindow = true) {
        SettingsContent(settingsPreviewUi(customPicture = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.DISPLAY, initialSheet = SheetKey.of(EssentialSheet.PICTURE))
    },
    GalleryEntry("settings", "sheet-interface-size", opensWindow = true) {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.DISPLAY,
            initialSheet = SheetKey.of(EssentialSheet.INTERFACE_SIZE))
    },
    GalleryEntry("settings", "sheet-driving-side", opensWindow = true) {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.CAR,
            initialSheet = SheetKey.of(EssentialSheet.DRIVING_SIDE))
    },
    GalleryEntry("settings", "sheet-advanced-choice", opensWindow = true) {
        SettingsContent(settingsPreviewUi(showAdvanced = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.DISPLAY,
            initialSheet = SheetKey.of(AdvancedSettings.all.first { it.id == "aa_resolution" }))
    },
).also { entries ->
    check(entries.map { it.area to it.name }.toSet().size == entries.size) {
        "Gallery entry names must be unique per area"
    }
}
