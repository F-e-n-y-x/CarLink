package com.carlink.app.gallery

import androidx.compose.runtime.Composable
import com.carlink.app.ui.settings.SettingsCategory
import com.carlink.app.ui.settings.SettingsChoice
import com.carlink.app.ui.settings.SettingsContent
import com.carlink.app.ui.settings.SettingsPreviewActions
import com.carlink.app.ui.settings.settingsPreviewState

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
    GalleryEntry("settings", "overview") {
        SettingsContent(settingsPreviewState(live = true), SettingsPreviewActions, onBack = {}, navigate = {})
    },
    GalleryEntry("settings", "display-theme-sheet", opensWindow = true) {
        SettingsContent(settingsPreviewState(), SettingsPreviewActions, onBack = {}, navigate = {},
            initialCategory = SettingsCategory.DISPLAY, initialChoice = SettingsChoice.THEME)
    },
    GalleryEntry("settings", "connection-paused") {
        SettingsContent(settingsPreviewState(paused = true), SettingsPreviewActions, onBack = {}, navigate = {},
            initialCategory = SettingsCategory.CONNECTION)
    },
).also { entries ->
    check(entries.map { it.area to it.name }.toSet().size == entries.size) {
        "Gallery entry names must be unique per area"
    }
}
