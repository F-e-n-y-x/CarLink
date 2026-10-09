package com.carlink.app.gallery

import com.carlink.app.ui.setup.SetupActions
import com.carlink.app.ui.setup.SetupContent
import com.carlink.app.ui.setup.SetupItem
import com.carlink.app.ui.setup.SetupPermission
import com.carlink.app.ui.setup.SetupPermissions
import com.carlink.app.ui.setup.SetupStep
import com.carlink.app.ui.setup.SetupUi
import com.carlink.core.connection.PairingSession
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkNotice
import com.carlink.core.core.LinkNoticeKind
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.detect.PairedPhone
import com.carlink.core.settings.VehicleProfile

private object GallerySetupActions : SetupActions

/** The first-time setup, one entry per step (and the pairing step's live states). */
internal fun setupEntries(): List<GalleryEntry> = with(HomeFakes) {
    val carName = "CarLink · Redmi Pad"
    // Bluetooth and location allowed; the rest still to allow.
    val permissions = SetupPermissions.items(35).map { (item, names) ->
        SetupPermission(item, names, granted = item == SetupItem.BLUETOOTH || item == SetupItem.LOCATION)
    }
    val allGranted = permissions.map { it.copy(granted = it.item != SetupItem.VPN) }
    fun ui(step: SetupStep, pairing: PairingSession.State = PairingSession.State.Waiting(carName), visible: Boolean = true,
           link: LinkState = LinkState.Idle, phone: PairedPhone? = null, cable: Boolean = false,
           known: List<PairedPhone> = emptyList(), perms: List<SetupPermission> = permissions) =
        SetupUi(step, perms, VehicleProfile.GENERIC, VehicleProfile.GENERIC, pairing, carName, visible, link, phone, known, cable)
    fun entry(name: String, content: () -> SetupUi) = GalleryEntry("setup", name) { SetupContent(content(), GallerySetupActions) }
    val s25 = PairedPhone(unknown.address, "Ayush’s S25 Ultra", PhoneKind.UNKNOWN, connected = false)
    listOf(
        entry("welcome") { ui(SetupStep.WELCOME) },
        entry("permissions") { ui(SetupStep.PERMISSIONS) },
        entry("car") { ui(SetupStep.CAR, perms = allGranted) },
        entry("pair-waiting") { ui(SetupStep.PAIR, visible = false, perms = allGranted, known = listOf(pixel)) },
        // Paired; the iPhone answered and is linking, slower than usual.
        entry("pair-found-iphone") {
            ui(SetupStep.PAIR, PairingSession.State.Paired(iphone.address, iphone.name, PhoneKind.IPHONE), perms = allGranted,
                phone = iphone, link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, iphone.name, 3, 4,
                    notice = LinkNotice(LinkNoticeKind.SLOW)))
        },
        entry("pair-kind-unknown") {
            ui(SetupStep.PAIR, PairingSession.State.Paired(s25.address, s25.name, PhoneKind.UNKNOWN), perms = allGranted, phone = s25)
        },
        entry("pair-android-connected") {
            ui(SetupStep.PAIR, PairingSession.State.Paired(pixel.address, pixel.name, PhoneKind.ANDROID), perms = allGranted, phone = pixel,
                link = LinkState(LinkStage.CONNECTED, Engine.ANDROID_AUTO, Transport.WIRELESS, pixel.name, 4, 4))
        },
        entry("pair-cable") {
            ui(SetupStep.PAIR, perms = allGranted, cable = true,
                link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.USB, "iPhone", 2, 4))
        },
        entry("pair-bluetooth-off") { ui(SetupStep.PAIR, PairingSession.State.BluetoothOff, perms = allGranted) },
        entry("done") { ui(SetupStep.DONE, perms = allGranted, phone = iphone) },
    )
}
