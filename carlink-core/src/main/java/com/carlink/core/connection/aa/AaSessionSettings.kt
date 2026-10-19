package com.carlink.core.connection.aa

import android.content.Context
import android.os.Bundle
import com.carlink.core.connection.PictureChoice
import com.carlink.core.connection.PicturePolicy
import com.carlink.core.core.Transport
import com.carlink.core.engine.AndroidAutoSettings
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.Picture
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.VehicleProfile
import com.carlink.core.settings.WifiEnvironment
import com.carlink.core.settings.WirelessPath
import com.carlink.core.settings.isValid
import kotlin.math.roundToInt

/** DiAuto's settings for one session, or why the chosen path cannot be configured. */
data class AaSessionValues(val values: Map<String, Any>, val problem: String? = null)

/**
 * The DiAuto settings for one Android Auto session (DA§11.1), as a pure mapping from the unified
 * settings, the transport and wireless path [SessionManager] chose, the vehicle profile and the car
 * name. [apply] writes them through `AndroidAutoSettingsProvider` in ":aa" (the only writer of
 * DiAuto's preferences, DA§7.1), which also mirrors the auto-start keys to device-protected storage.
 *
 * Every key the engine would otherwise take from its own defaults or from an earlier session is
 * written every time, so no session inherits another's wireless route (DA§10.1, §10.4, §10.6).
 * `static-bssid` goes back to "0" at stop too (in ":aa", AaCommandHandler.stop).
 * The wake-poke targets (`auto-start-bt-macs`) are set by the ":aa" receiver at start.
 */
object AaSessionSettings {
    /**
     * @param panelDensityDpi the head unit's own density, which scales Android Auto's interface.
     * @param wifi the car's Wi-Fi right now; Same Wi-Fi needs its client interface and the router's BSSID.
     */
    fun values(
        settings: UnifiedSettings,
        transport: Transport,
        path: WirelessPath?,
        profile: VehicleProfile,
        carName: String,
        panelDensityDpi: Int,
        wifi: WifiEnvironment? = null,
        picture: PictureChoice = PicturePolicy.ENGINE_DEFAULTS,
    ): AaSessionValues {
        var problem: String? = null
        val values = buildMap<String, Any> {
            // Native wireless Android Auto, the only no-companion route; a stray mode 1/2 makes
            // every wireless start a no-op (DA§10.4).
            put("wifi-connection-mode", 3)
            // DiAuto 0.3.12 keeps every wireless transport down when its connection types leave
            // WiFi out (WirelessScopePolicy). CarLink decides USB or wireless per session
            // (usbOnlySession), so the engine's own filter always allows both.
            put("connection-modes", setOf("usb", "wifi"))
            // CarLink owns every entry point (DA§10.6.2, §11.1): the engine's hibernate-wake launch
            // and its USB/Wi-Fi auto-starts stay off.
            put("auto-start-on-boot", false)
            put("auto-start-on-screen-on", false)
            put("auto-start-on-usb", false)
            put("auto-start-on-wifi", false)
            put("auto-start-self-mode", false)
            // CarLink performs the AOA switch itself; otherwise every service start switches any
            // allowed phone that is plugged in, wireless sessions included (DA§10.6.3).
            put("auto-connect-last-session", false)
            // true would finish all of CarLink's tasks, Home included (DA§10.6.5).
            put("kill-on-disconnect", false)
            // The phone rejoins a network it saved (open-headunit PR #1006, DA§10.11).
            put("wifi-direct-stable-identity", true)
            put("vehicle-display-name", carName)
            // A tablet or generic head unit is no A2DP sink: true omits Android Auto's media sink
            // and leaves no music at all (DA§4.5 item 4). DiLink's own default is true (DA§12 #9).
            put("music-via-bluetooth", when (profile) {
                VehicleProfile.BYD_DILINK -> true
                VehicleProfile.GENERIC -> settings.soundThroughCarBluetooth
            })
            // BYD's HUD/cluster outputs only on a BYD (DA§12.2).
            put("byd-navigation-enabled", profile == VehicleProfile.BYD_DILINK && settings.bydDirections)
            // DiAuto 0.3.12's BYD dashboard map (a second video stream announced to the phone and a
            // window on the cluster display). Off everywhere: CarLink has no setting for it yet,
            // and it is untested beyond DiLink 5.1. A BYD-only setting can turn it on later.
            put("cluster-map-enabled", false)
            putPicture(settings, panelDensityDpi, picture)
            put("night-mode", when (settings.dayNight) {
                DayNight.AUTOMATIC -> 0 // sunrise/sunset
                DayNight.DAY -> 1
                DayNight.NIGHT -> 2
                DayNight.SCHEDULE -> 3
            })
            put("night-mode-manual-start", settings.nightStartMinute)
            put("night-mode-manual-end", settings.nightEndMinute)
            put("right-hand-drive", settings.rightHandDrive)
            put("gps-navigation", settings.sendCarLocation)

            // The wireless route. A USB session writes the Wi-Fi Direct route: the engine arms
            // wireless in the background of every service start (DA§2.3), and must not do so with
            // an earlier session's shared network and BSSID.
            val route = if (transport == Transport.USB) WirelessPath.WIFI_DIRECT else path ?: WirelessPath.WIFI_DIRECT
            when (route) {
                WirelessPath.WIFI_DIRECT -> {
                    put("native-ap-transport", 0)
                    put("hotspot-interface", "")
                    put("hotspot-ssid", "")
                    put("hotspot-password", "")
                    put("static-bssid", "0")
                    put("auto-enable-hotspot", false)
                }
                WirelessPath.CAR_HOTSPOT -> {
                    // Empty name/password: the engine reads the system's own AP config where the
                    // firmware allows it (DA§3.3.2). The interface is found by its heuristic.
                    put("native-ap-transport", 1)
                    put("hotspot-interface", "")
                    put("hotspot-ssid", if (settings.carHotspot.isValid()) settings.carHotspot.ssid else "")
                    put("hotspot-password", if (settings.carHotspot.isValid()) settings.carHotspot.password else "")
                    put("static-bssid", "0")
                    // Never switch the driver's own hotspot on or off.
                    put("auto-enable-hotspot", false)
                }
                WirelessPath.SAME_WIFI -> {
                    val iface = wifi?.joinedInterface
                    val bssid = wifi?.joinedBssid
                    if (iface == null || bssid == null || !settings.homeWifi.isValid()) {
                        problem = "Same Wi-Fi needs the car's Wi-Fi interface, the router's BSSID and a saved network"
                    } else {
                        // DiAuto's access-point route with every value named by hand (DA§3.3.4):
                        // the phone's specifier needs the exact BSSID.
                        put("native-ap-transport", 1)
                        put("hotspot-interface", iface)
                        put("hotspot-ssid", settings.homeWifi.ssid)
                        put("hotspot-password", settings.homeWifi.password)
                        put("static-bssid", bssid)
                        put("auto-enable-hotspot", false)
                    }
                }
            }
        }
        return AaSessionValues(values, problem)
    }

    private fun MutableMap<String, Any>.putPicture(settings: UnifiedSettings, panelDensityDpi: Int, picture: PictureChoice) {
        when (settings.picture) {
            // The device's decoder decides ([PicturePolicy]): DiAuto's own Automatic at 60 fps unless it needs less.
            Picture.AUTOMATIC -> {
                put("resolutionId", picture.aaResolutionId)
                put("fps-limit", picture.aaFps)
                picture.aaCodec?.let { put("video-codec", it) }
            }
            Picture.BEST -> { put("resolutionId", 3); put("fps-limit", 60) }
            Picture.BALANCED -> { put("resolutionId", 2); put("fps-limit", 30) }
            Picture.LIGHT -> { put("resolutionId", 1); put("fps-limit", 30) }
            Picture.CUSTOM -> Unit // Advanced owns the exact values.
        }
        when (settings.interfaceSize) {
            // Lower DPI draws Android Auto's controls smaller, matching CarPlay's wider panel.
            InterfaceSize.SMALL -> put("dpi-pixel-density", (panelDensityDpi * 0.85f).roundToInt())
            InterfaceSize.MEDIUM -> put("dpi-pixel-density", 0)
            InterfaceSize.LARGE -> put("dpi-pixel-density", (panelDensityDpi * 1.2f).roundToInt())
            InterfaceSize.CUSTOM -> Unit
        }
    }

    /** Writes [values] into DiAuto's settings in ":aa". Blocking IPC: call off the main thread. */
    fun apply(context: Context, values: AaSessionValues) {
        val bundle = Bundle()
        values.values.forEach { (key, value) ->
            when (value) {
                is Int -> bundle.putInt(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is String -> bundle.putString(key, value)
            }
        }
        AndroidAutoSettings.write(context, bundle)
    }
}
