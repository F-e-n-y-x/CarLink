package com.carlink.app.gallery

import com.carlink.core.settings.CarBrand
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.Transport
import com.carlink.core.detect.BluetoothAvailability
import com.carlink.app.ui.about.AboutContent
import com.carlink.app.ui.about.AboutSheet
import com.carlink.app.ui.about.previewFacts
import com.carlink.app.ui.diagnostics.DiagnosticsActions
import com.carlink.app.ui.diagnostics.DiagnosticsContent
import com.carlink.app.ui.diagnostics.DiagnosticsUi
import com.carlink.app.ui.diagnostics.previewChecks
import com.carlink.app.ui.diagnostics.previewLink
import com.carlink.app.ui.diagnostics.previewLog
import com.carlink.core.connection.DETAIL_PHONE_NOT_ANSWERING
import com.carlink.app.ui.NetworkPreview
import com.carlink.app.ui.home.HomeContent
import com.carlink.app.ui.home.NetworkSheet
import com.carlink.app.ui.home.PhonesSheet
import com.carlink.core.settings.AdvancedSettings
import com.carlink.core.settings.SettingsCategory
import com.carlink.app.ui.settings.EssentialSheet
import com.carlink.app.ui.settings.SettingsContent
import com.carlink.app.ui.settings.SettingsPreviewActions
import com.carlink.app.ui.settings.SheetKey
import com.carlink.app.ui.settings.settingsPreviewUi

/**
 * One screen state in the gallery. [content] is a stateless screen fed with fake state; the
 * harness wraps it in CarLinkTheme for every [GalleryConfig], so do not add a theme here.
 * The image lands at docs/screenshots/[area]/[area]_[name]_<config>_<theme>.webp (half size).
 *
 * Set [opensWindow] when the state shows a dialog or bottom sheet: those are separate windows
 * that span the whole panel, so the emulated split-screen configs cannot place them and skip
 * the entry.
 */
class GalleryEntry(
    val area: String,
    val name: String,
    val opensWindow: Boolean = false,
    /** Config names to render on; null means every config that is not opt-in. */
    val configs: Set<String>? = null,
    val content: @Composable () -> Unit,
)

/**
 * Every registered screen state. To add a screen, append entries that call its stateless
 * content composable (the one its @Preview functions use) with preview fakes.
 */
val galleryEntries: List<GalleryEntry> = (homeEntries() + settingsEntries() + diagnosticsEntries() + aboutEntries() + switcherEntries() + updateEntries() +
    setupEntries()).also { entries ->
        check(entries.map { it.area to it.name }.toSet().size == entries.size) {
            "Gallery entry names must be unique per area"
        }
    }

private object GalleryDiagnosticsActions : DiagnosticsActions

private fun home(name: String, opensWindow: Boolean = false, content: @Composable () -> Unit) =
    GalleryEntry("home", name, opensWindow, content = content)

private fun homeEntries(): List<GalleryEntry> = with(HomeFakes) { listOf(
    home("permission") { HomeContent(ui(permissionsNeeded = true, phones = emptyList()), actions) },
    home("bluetooth-off") {
        HomeContent(ui(bluetooth = BluetoothAvailability.OFF, phones = emptyList()), actions)
    },
    home("ready-no-phone") { HomeContent(ui(phones = emptyList(), network = null), actions) },
    // A known iPhone, plus the one-time offer to use the car's current Wi-Fi.
    home("ready-iphone-same-wifi-offer") {
        HomeContent(ui(phones = listOf(iphone, pixel), network = sameWifiOffer), actions)
    },
    home("connecting-carplay-usb") {
        HomeContent(ui(link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB, iphone.name, 2, 6,
            "Authenticating"), phones = listOf(iphoneConnected)), actions)
    },
    // Android Auto waits for the phone's Bluetooth; the phone is not connected, so Home offers Bluetooth settings.
    home("connecting-android-auto-wireless-waiting") {
        HomeContent(ui(link = LinkState(LinkStage.WAITING, Engine.ANDROID_AUTO, Transport.WIRELESS, pixel.name, 1, 5,
            "Waiting for the phone"), phones = listOf(pixel, iphone)), actions)
    },
    // Wireless, and the iPhone has not answered for 10 s: calm caution, held at the first step.
    home("connecting-not-answering") {
        HomeContent(ui(link = LinkState(LinkStage.WAITING, Engine.CARPLAY, Transport.WIRELESS, iphone.name, 1, 4,
            DETAIL_PHONE_NOT_ANSWERING, notice = com.carlink.core.core.LinkNotice(com.carlink.core.core.LinkNoticeKind.NOT_ANSWERING))),
            actions)
    },
    // Why it waits (SessionState.Active.notice): a fresh Wi-Fi Direct link after a slow join.
    home("connecting-notice-wifi") {
        HomeContent(ui(link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, iphone.name, 3, 4,
            notice = com.carlink.core.core.LinkNotice(com.carlink.core.core.LinkNoticeKind.RECOVERING,
                com.carlink.core.core.LinkRecovery.FRESH_WIFI_DIRECT))), actions)
    },
    // A path-related failure moved the attempt to the car hotspot.
    home("connecting-notice-next-path") {
        HomeContent(ui(link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, iphone.name, 2, 4,
            notice = com.carlink.core.core.LinkNotice(com.carlink.core.core.LinkNoticeKind.RECOVERING,
                com.carlink.core.core.LinkRecovery.TRY_CAR_HOTSPOT)), network = hotspot), actions)
    },
    // Android Auto retrying after the phone did not answer over Bluetooth.
    home("connecting-notice-retrying") {
        HomeContent(ui(link = LinkState(LinkStage.CONNECTING, Engine.ANDROID_AUTO, Transport.WIRELESS, pixel.name, 2, 4,
            notice = com.carlink.core.core.LinkNotice(com.carlink.core.core.LinkNoticeKind.RETRYING,
                reason = LinkProblem.BLUETOOTH)), phones = listOf(pixel.copy(connected = true))), actions)
    },
    // iOS 27: the iPhone plays sound but sends no picture; one tap turns on Direct video output and reconnects.
    home("connecting-notice-no-picture") {
        HomeContent(ui(link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, iphone.name, 4, 4,
            notice = com.carlink.core.core.LinkNotice(com.carlink.core.core.LinkNoticeKind.NO_PICTURE)),
            phones = listOf(iphoneConnected)), actions)
    },
    // A USB phone that has not answered for 10 s (locked, or waiting for its prompt).
    home("connecting-not-answering-usb") {
        HomeContent(ui(link = LinkState(LinkStage.WAITING, Engine.ANDROID_AUTO, Transport.USB, pixel.name, 1, 4,
            DETAIL_PHONE_NOT_ANSWERING, notice = com.carlink.core.core.LinkNotice(com.carlink.core.core.LinkNoticeKind.NOT_ANSWERING)),
            phones = listOf(pixel)), actions)
    },
    // A known phone's record under the headline.
    home("ready-iphone-usual") {
        HomeContent(ui(phones = listOf(iphone, pixel), network = NetworkPreview(com.carlink.core.settings.WirelessPath.WIFI_DIRECT,
            ssid = null)).copy(usual = com.carlink.app.ui.home.PhoneUsual(5_200,
            com.carlink.core.settings.WirelessPath.WIFI_DIRECT)), actions)
    },
    home("connected-carplay") {
        val since = remember { SystemClock.elapsedRealtime() - 754_000 }
        HomeContent(ui(link = LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.WIRELESS, iphone.name, 6, 6),
            phones = listOf(iphoneConnected, pixel), network = sameWifi, connectedSince = since), actions)
    },
    home("failed-carplay-auth-missing") {
        HomeContent(ui(link = failed(LinkProblem.CARPLAY_AUTH_MISSING, transport = Transport.USB)), actions)
    },
    home("failed-car-hotspot-off") { HomeContent(ui(link = failed(LinkProblem.CAR_HOTSPOT_OFF)), actions) },
    // Connection v2 problems with one-tap fixes: a radio switch, and a path switch with a second action.
    home("failed-wifi-off") { HomeContent(ui(link = failed(LinkProblem.WIFI_OFF)), actions) },
    home("failed-pairing-lost") { HomeContent(ui(link = failed(LinkProblem.PAIRING_LOST)), actions) },
    home("failed-iphone-unreached") {
        HomeContent(ui(link = failed(LinkProblem.PHONE_NEVER_REACHED_CAR), network = sameWifi), actions)
    },
    home("connected-carplay-usb") {
        val since = remember { SystemClock.elapsedRealtime() - 1_394_000 }
        HomeContent(ui(link = LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.USB, "iPhone", 6, 6),
            phones = emptyList(), connectedSince = since), actions)
    },
    home("sheet-phones-usb", opensWindow = true) {
        val ui = ui(link = LinkState(LinkStage.CONNECTED, Engine.CARPLAY, Transport.USB, iphone.name, 6, 6),
            phones = listOf(iphone, pixel, unknown), connectedSince = remember { SystemClock.elapsedRealtime() - 60_000 })
            .let { it.copy(home = it.home.copy(usbKinds = setOf(com.carlink.core.core.PhoneKind.IPHONE))) }
        HomeContent(ui, actions)
        PhonesSheet(ui, actions, onDismiss = {})
    },
    home("connected-android-auto-usb") {
        val since = remember { SystemClock.elapsedRealtime() - 3_725_000 }
        HomeContent(ui(link = LinkState(LinkStage.CONNECTED, Engine.ANDROID_AUTO, Transport.USB, pixel.name, 6, 6),
            phones = listOf(pixel.copy(connected = true), iphone), connectedSince = since), actions)
    },
    // The stereo's Bluetooth is an external module apps cannot use (ExternalBluetoothProbe): cable advice, no Try again.
    home("failed-external-bluetooth") {
        HomeContent(ui(link = failed(LinkProblem.EXTERNAL_BLUETOOTH, engine = Engine.ANDROID_AUTO), phones = listOf(pixel)), actions)
    },
    // Wireless through the stereo's Bluetooth module (experimental) could not reach the module.
    home("failed-external-module") {
        HomeContent(ui(link = failed(LinkProblem.EXTERNAL_MODULE, engine = Engine.ANDROID_AUTO), phones = listOf(pixel)), actions)
    },
    home("failed-engine") {
        HomeContent(ui(link = failed(LinkProblem.ENGINE, engine = Engine.ANDROID_AUTO), phones = listOf(pixel, iphone)), actions)
    },
    home("sheet-phones", opensWindow = true) {
        val ui = ui(phones = listOf(iphone, pixel, unknown))
        HomeContent(ui, actions)
        PhonesSheet(ui, actions, onDismiss = {})
    },
    home("sheet-network", opensWindow = true) {
        val ui = ui(phones = listOf(iphone, pixel))
        HomeContent(ui, actions)
        NetworkSheet(ui, actions, onDismiss = {})
    },
) }

private fun diagnosticsEntries(): List<GalleryEntry> = listOf(
    GalleryEntry("diagnostics", "idle") {
        DiagnosticsContent(DiagnosticsUi(LinkState.Idle, null, null, previewChecks, previewLog()), GalleryDiagnosticsActions)
    },
    // An FYT-type stereo whose Bluetooth is a ZLink-type module: the warning keeps the summary from "Everything looks ready".
    GalleryEntry("diagnostics", "stereo-bluetooth-external") {
        DiagnosticsContent(DiagnosticsUi(LinkState.Idle, null, null, com.carlink.app.ui.diagnostics.previewStereoChecks(),
            previewLog()), GalleryDiagnosticsActions)
    },
    GalleryEntry("diagnostics", "stereo-bluetooth-maybe") {
        DiagnosticsContent(DiagnosticsUi(LinkState.Idle, null, null, com.carlink.app.ui.diagnostics.previewStereoChecks(likely = false),
            previewLog()), GalleryDiagnosticsActions)
    },
    GalleryEntry("diagnostics", "connecting") {
        val started = remember { SystemClock.elapsedRealtime() - 12_000 }
        DiagnosticsContent(DiagnosticsUi(previewLink, null, started, previewChecks, previewLog()), GalleryDiagnosticsActions)
    },
    // Diagnostics › Phones: two phones' records (SessionManager.health) and a live notice in the session card.
    GalleryEntry("diagnostics", "phones-health") {
        val started = remember { SystemClock.elapsedRealtime() - 31_000 }
        val link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, "Sara’s iPhone", 3, 4,
            notice = com.carlink.core.core.LinkNotice(com.carlink.core.core.LinkNoticeKind.RECOVERING,
                com.carlink.core.core.LinkRecovery.FRESH_WIFI_DIRECT))
        DiagnosticsContent(DiagnosticsUi(link, null, started, previewChecks, previewLog(),
            com.carlink.app.ui.diagnostics.previewHealth()), GalleryDiagnosticsActions)
    },
    // The Phones card alone (in the full screen it sits below the fold on most windows).
    GalleryEntry("diagnostics", "phones-card") {
        com.carlink.app.ui.premium.PremiumScreen("Diagnostics", onBack = {}) { margin ->
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = margin)
                .widthIn(max = 900.dp)) {
                com.carlink.app.ui.diagnostics.PhonesHealthCard(com.carlink.app.ui.diagnostics.previewHealth())
            }
        }
    },
    // Report on GitHub: the confirmation sheet, and the QR code when the stereo has no browser.
    GalleryEntry("diagnostics", "report-confirm", opensWindow = true, configs = setOf("tablet", "car-landscape")) {
        DiagnosticsContent(DiagnosticsUi(LinkState.Idle, null, null, previewChecks, previewLog()), GalleryDiagnosticsActions)
        com.carlink.app.ui.diagnostics.IssueConfirmSheet(com.carlink.app.ui.diagnostics.previewIssueReport(), onOpen = {}, onCancel = {})
    },
    GalleryEntry("diagnostics", "report-qr", opensWindow = true, configs = setOf("tablet", "car-landscape")) {
        DiagnosticsContent(DiagnosticsUi(LinkState.Idle, null, null, previewChecks, previewLog()), GalleryDiagnosticsActions)
        com.carlink.app.ui.diagnostics.IssueQrSheet(com.carlink.app.ui.diagnostics.previewIssueReport(), onShareLog = {}, onDone = {})
    },
    GalleryEntry("diagnostics", "phones-empty") {
        DiagnosticsContent(DiagnosticsUi(LinkState.Idle, null, null, previewChecks, previewLog(), emptyList()), GalleryDiagnosticsActions)
    },
)

private fun aboutEntries(): List<GalleryEntry> = listOf(
    GalleryEntry("about", "main") { AboutContent(previewFacts, onBack = {}) },
    GalleryEntry("about", "sheet-licenses", opensWindow = true) {
        AboutContent(previewFacts, onBack = {}, initialSheet = AboutSheet.LICENSES)
    },
)

private fun settingsEntries(): List<GalleryEntry> = listOf(
    // Root: the rail with Connection on wide screens, the category grid on narrow ones.
    GalleryEntry("settings", "root") {
        SettingsContent(settingsPreviewUi(), SettingsPreviewActions, onBack = {})
    },
    GalleryEntry("settings", "connection-paused") {
        SettingsContent(settingsPreviewUi(paused = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.CONNECTION)
    },
    // An FYT stereo with an external Bluetooth module: the experimental module switch and its note.
    GalleryEntry("settings", "connection-external-module") {
        SettingsContent(settingsPreviewUi(showAdvanced = true).copy(stereoBluetoothExternal = true, stereoBluetoothFyt = true,
            connection = settingsPreviewUi().connection.copy(wirelessOnExternalBluetooth = true)), SettingsPreviewActions, onBack = {},
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
    // Display › Advanced, CarPlay's last rows (below the fold above), with Direct video output.
    GalleryEntry("settings", "display-advanced-carplay") {
        com.carlink.app.ui.premium.PremiumScreen("Display", onBack = {}) { margin ->
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = margin)
                .widthIn(max = 900.dp)) {
                com.carlink.app.ui.settings.GlassGroup(header = { com.carlink.app.ui.settings.EngineHeader(Engine.CARPLAY) }) {
                    com.carlink.app.ui.settings.engineRows(SettingsCategory.DISPLAY, com.carlink.core.settings.Tier.ADVANCED)
                        .first { it.first == Engine.CARPLAY }.second.dropWhile { it.id != "cp_adapt_pip" }.forEachIndexed { index, setting ->
                            if (index > 0) com.carlink.app.ui.settings.GroupDivider()
                            com.carlink.app.ui.settings.EngineSettingRow(setting, settingsPreviewUi().advancedValues,
                                SettingsPreviewActions, openSheet = {})
                        }
                }
            }
        }
    },
    GalleryEntry("settings", "sound-advanced-lab") {
        SettingsContent(settingsPreviewUi(showAdvanced = true, lab = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.SOUND)
    },
    GalleryEntry("settings", "general-lab") {
        SettingsContent(settingsPreviewUi(lab = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.GENERAL)
    },
    // Settings › Car with the car brand row (Hyundai chosen on a generic screen).
    GalleryEntry("settings", "car-brand") {
        SettingsContent(settingsPreviewUi(byd = false, carBrand = CarBrand.HYUNDAI), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.CAR)
    },
    GalleryEntry("settings", "sheet-car-brand", opensWindow = true) {
        SettingsContent(settingsPreviewUi(byd = false, carBrand = CarBrand.HYUNDAI), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.CAR, initialSheet = SheetKey.of(EssentialSheet.CAR_BRAND))
    },
    // The brand sheet with BYD chosen: the DiLink extras explained.
    GalleryEntry("settings", "sheet-car-brand-byd", opensWindow = true) {
        SettingsContent(settingsPreviewUi(byd = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.CAR, initialSheet = SheetKey.of(EssentialSheet.CAR_BRAND))
    },
    // Nothing chosen on a generic screen: the row reads "Other / Generic".
    GalleryEntry("settings", "car-brand-not-chosen") {
        SettingsContent(settingsPreviewUi(byd = false), SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.CAR)
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
    // Settings › General › Visual effects; rendered only by the low-end head-unit configs.
    GalleryEntry("settings", "sheet-rendering", opensWindow = true, configs = emptySet()) {
        // As on a low-end head unit, where Automatic picks lite.
        val ui = settingsPreviewUi().let { it.copy(display = it.display.copy(autoLite = true)) }
        SettingsContent(ui, SettingsPreviewActions, onBack = {}, startCategory = SettingsCategory.GENERAL,
            initialSheet = SheetKey.of(EssentialSheet.RENDERING))
    },
    GalleryEntry("settings", "sheet-advanced-choice", opensWindow = true) {
        SettingsContent(settingsPreviewUi(showAdvanced = true), SettingsPreviewActions, onBack = {},
            startCategory = SettingsCategory.DISPLAY,
            initialSheet = SheetKey.of(AdvancedSettings.all.first { it.id == "aa_resolution" }))
    },
)

