package com.carlink.app.gallery

import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.NetworkPreview
import com.carlink.app.ui.home.HomeActions
import com.carlink.app.ui.home.HomeUi
import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.PhoneKind
import com.carlink.core.core.Transport
import com.carlink.core.detect.BluetoothAvailability
import com.carlink.core.detect.PairedPhone
import com.carlink.core.settings.WirelessPath

/** Fake state for Home's gallery entries. */
internal object HomeFakes {
    val iphone = PairedPhone("AA:BB:CC:00:00:01", "Sara’s iPhone", PhoneKind.IPHONE, connected = false)
    val iphoneConnected = iphone.copy(connected = true)
    val pixel = PairedPhone("AA:BB:CC:00:00:02", "Pixel 8", PhoneKind.ANDROID, connected = false)
    val unknown = PairedPhone("AA:BB:CC:00:00:03", "Galaxy Buds", PhoneKind.UNKNOWN, connected = false)

    val prefs = ConnectionPrefs(autoConnectUsb = true, autoConnectBluetooth = true, startOnBoot = true, autoConnectPaused = false)

    val hotspot = NetworkPreview(WirelessPath.CAR_HOTSPOT, ssid = null)
    /** Same Wi-Fi chosen, the screen on "Home Wi-Fi", no password saved yet. */
    val sameWifiNoPassword = NetworkPreview(WirelessPath.SAME_WIFI, ssid = "Home Wi-Fi", joinedSsid = "Home Wi-Fi", canOfferSameWifi = true)
    val sameWifi = NetworkPreview(WirelessPath.SAME_WIFI, ssid = "Home Wi-Fi", joinedSsid = "Home Wi-Fi")

    val actions: HomeActions = object : HomeActions {}

    fun ui(
        link: LinkState = LinkState.Idle,
        phones: List<PairedPhone> = listOf(iphone),
        defaultPhone: PairedPhone? = phones.firstOrNull(),
        bluetooth: BluetoothAvailability = BluetoothAvailability.READY,
        network: NetworkPreview? = hotspot,
        connectedSince: Long? = null,
        permissionsNeeded: Boolean = false,
    ) = HomeUi(HomeState(link, phones, bluetooth, defaultPhone, connectedSince), prefs, network, permissionsNeeded)

    fun failed(problem: LinkProblem, engine: Engine = Engine.CARPLAY, transport: Transport = Transport.WIRELESS) =
        LinkState(LinkStage.FAILED, engine, transport, if (engine == Engine.CARPLAY) iphone.name else pixel.name,
            problem = problem, detail = "Failed")
}
