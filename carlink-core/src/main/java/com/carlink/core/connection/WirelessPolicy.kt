package com.carlink.core.connection

import com.carlink.core.core.Engine
import com.carlink.core.settings.ConnectionMethod
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.WifiEnvironment
import com.carlink.core.settings.WirelessPath
import com.carlink.core.settings.isValid

/**
 * Which wireless paths a session tries, in order (design/REBUILD_V2.md §3).
 *
 * The receiver's own link comes first because it is what every shipping product uses and what
 * the engines' authors recommend: Open Headunit rates Wi-Fi Direct "Best for stability. No
 * hotspot required", and it is the path proven for both engines on the user's tablet (DA§3,
 * DP§11.1). A head unit's own hotspot is preferred for CarPlay when its details are known (DiPlay's
 * maintainer recommends it on 5 GHz). The shared router ("Same Wi-Fi") is never chosen
 * automatically: Android Auto then takes the phone off its Wi-Fi internet and needs a typed
 * password and the exact BSSID, and CarPlay needs a router that passes multicast DNS
 * (DA§10.2, DP§3.4). The driver can still pick it.
 */
object WirelessPolicy {
    /** What the policy needs to know about this device and the phone. */
    data class Inputs(
        val engine: Engine,
        val settings: UnifiedSettings,
        val wifi: WifiEnvironment,
        /** True on a car head unit, false on a tablet or phone used as the display. */
        val headUnit: Boolean,
        /** The path that last connected for this phone, if any. */
        val lastWorking: WirelessPath? = null,
    )

    /** The paths to try in order; never empty. */
    fun candidates(inputs: Inputs): List<WirelessPath> {
        val s = inputs.settings
        return when (s.connectionMethod) {
            ConnectionMethod.SAME_WIFI -> listOf(WirelessPath.SAME_WIFI)
            ConnectionMethod.CAR_HOTSPOT -> listOf(WirelessPath.CAR_HOTSPOT)
            ConnectionMethod.WIFI_DIRECT -> listOf(WirelessPath.WIFI_DIRECT)
            ConnectionMethod.AUTOMATIC -> automatic(inputs)
        }
    }

    private fun automatic(inputs: Inputs): List<WirelessPath> {
        val hotspot = hotspotUsable(inputs.settings, inputs.wifi)
        val order = buildList {
            if (hotspot && inputs.headUnit && inputs.engine == Engine.CARPLAY) add(WirelessPath.CAR_HOTSPOT)
            add(WirelessPath.WIFI_DIRECT)
            if (hotspot && WirelessPath.CAR_HOTSPOT !in this) add(WirelessPath.CAR_HOTSPOT)
        }
        // What worked last time for this phone goes first, as long as Automatic would use it.
        val last = inputs.lastWorking
        return if (last != null && last in order) listOf(last) + (order - last) else order
    }

    /**
     * The car's hotspot can be handed to a phone: its name and password are known (typed by the
     * driver, or read from the system where the firmware allows it) and it is not known to be off.
     */
    fun hotspotUsable(settings: UnifiedSettings, wifi: WifiEnvironment): Boolean =
        (settings.carHotspot.isValid() && settings.carHotspot.password.isNotEmpty() && wifi.carHotspotOn != false) ||
            (wifi.systemHotspot != null && wifi.carHotspotOn == true)
}
