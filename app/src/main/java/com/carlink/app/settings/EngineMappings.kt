package com.carlink.app.settings

import com.carlink.app.core.Engine
import kotlin.math.roundToInt

/** The wireless network a session will use, chosen by [WirelessPathSelector]. */
enum class WirelessPath { SAME_WIFI, CAR_HOTSPOT, WIFI_DIRECT }

/** What CarLink knows about the car's Wi-Fi right before a wireless session. */
data class WifiEnvironment(
    /** Wi-Fi networks the car is joined to as a client (not counting its own hotspot). */
    val joinedNetworks: Int,
    /** SSID of that network when exactly one and readable (needs location permission). */
    val joinedSsid: String?,
    /** Whether the car's own hotspot is on; null when the firmware does not say. */
    val carHotspotOn: Boolean?,
    /** The router's BSSID for [joinedSsid] (needs location permission), or null. */
    val joinedBssid: String? = null,
    /** The car's Wi-Fi client interface (usually wlan0), or null. */
    val joinedInterface: String? = null,
    /** Whether [joinedSsid] needs no password (open or OWE); null when Android does not say. */
    val joinedOpen: Boolean? = null,
    /** An Android Auto phone already refused [joinedSsid] with the saved password (see WirelessRefusals). */
    val androidAutoRefusedJoined: Boolean = false,
    /** The car's own hotspot name and password, read from the system where the firmware allows it. */
    val systemHotspot: WifiDetails? = null,
)

/**
 * Picks the wireless path (design/REBUILD_PLAN.md): Same Wi-Fi when possible, then the car
 * hotspot, then Wi-Fi Direct.
 *
 * CarPlay hands the iPhone the router's details over iAP2. Android Auto's protocol hands the
 * phone a network to join too; DiAuto normally fills it with its own hotspot, and CarLink fills
 * it with the shared router instead, which also needs the router's BSSID and the car's Wi-Fi
 * interface (both readable with location permission).
 */
object WirelessPathSelector {
    fun select(engine: Engine, settings: UnifiedSettings, wifi: WifiEnvironment): WirelessPath {
        val joinedSaved = settings.homeWifi.isValid() &&
            wifi.joinedNetworks == 1 && wifi.joinedSsid != null && wifi.joinedSsid == settings.homeWifi.ssid
        // A phone handed a secured network without its password refuses it at once, so an empty
        // password only counts on a network Android reports as open.
        val passwordKnown = settings.homeWifi.password.isNotEmpty() || wifi.joinedOpen == true
        val sameWifiUsable = joinedSaved && passwordKnown && (engine == Engine.CARPLAY ||
            (wifi.joinedBssid != null && wifi.joinedInterface != null && !wifi.androidAutoRefusedJoined))
        val hotspotUsable = (settings.carHotspot.isValid() && wifi.carHotspotOn != false) ||
            (wifi.systemHotspot != null && wifi.carHotspotOn == true)
        return when (settings.connectionMethod) {
            ConnectionMethod.SAME_WIFI -> when {
                // CarPlay's pre-flight reports missing details itself; only a saved entry known
                // to be unusable (secured network, no password) is not honoured.
                sameWifiUsable || (engine == Engine.CARPLAY && (passwordKnown || !settings.homeWifi.isValid())) ->
                    WirelessPath.SAME_WIFI
                hotspotUsable -> WirelessPath.CAR_HOTSPOT
                else -> WirelessPath.WIFI_DIRECT
            }
            ConnectionMethod.CAR_HOTSPOT -> WirelessPath.CAR_HOTSPOT
            ConnectionMethod.WIFI_DIRECT -> WirelessPath.WIFI_DIRECT
            ConnectionMethod.AUTOMATIC -> when {
                sameWifiUsable -> WirelessPath.SAME_WIFI
                hotspotUsable -> WirelessPath.CAR_HOTSPOT
                else -> WirelessPath.WIFI_DIRECT
            }
        }
    }
}

/** SSID ≤32 UTF-8 bytes, password empty (open network) or 8–63 characters: what both engines accept. */
fun WifiDetails.isValid(): Boolean {
    val ssidBytes = ssid.toByteArray(Charsets.UTF_8).size
    return ssidBytes in 1..32 && (password.isEmpty() || password.length in 8..63)
}

/** CarPlay (DiPlay) values for the unified settings. Null means "leave the engine's value alone". */
data class CarPlayValues(
    val wirelessMode: String?,
    val displayScalePercent: Int?,
    val fps: Int?,
    val widthPhysicalMm: Int?,
    val nightModeKey: String,
    val nightStartMinute: Int,
    val nightEndMinute: Int,
    val rightHandDrive: Boolean,
    val carBluetoothAudio: Boolean,
    val locationReporting: Boolean,
    val bydDirections: Boolean,
    val carHotspot: WifiDetails,
    val homeWifi: WifiDetails,
)

/**
 * Pure translations from [UnifiedSettings] to each engine's own keys and units, so every rule is
 * unit tested. Engine facts (from the settings inventories):
 * - CarPlay resolution is a percentage of the window and its UI size is a reported panel width
 *   in mm (wider = smaller controls; 300 mm is DiPlay's default); fps is 30 or 60.
 * - Android Auto resolution is a fixed list (resolutionId 0 auto, 1 480p, 2 720p, 3 1080p) and its
 *   UI size is the DPI it reports (0 = the panel's own density); fps is 30 or 60.
 */
object EngineMappings {
    fun carPlay(settings: UnifiedSettings, path: WirelessPath?): CarPlayValues {
        val (scale, fps) = when (settings.picture) {
            Picture.AUTOMATIC -> 100 to 30 // DiPlay's own defaults
            Picture.BEST -> 100 to 60
            Picture.BALANCED -> 100 to 30
            Picture.LIGHT -> 75 to 30
            Picture.CUSTOM -> null to null
        }
        val widthMm = when (settings.interfaceSize) {
            InterfaceSize.SMALL -> 350
            InterfaceSize.MEDIUM -> 300
            InterfaceSize.LARGE -> 250
            InterfaceSize.CUSTOM -> null
        }
        return CarPlayValues(
            wirelessMode = when (path) {
                WirelessPath.SAME_WIFI -> "EXISTING_WIFI"
                WirelessPath.CAR_HOTSPOT -> "MANUAL"
                WirelessPath.WIFI_DIRECT -> "WIFI_P2P"
                null -> null
            },
            displayScalePercent = scale,
            fps = fps,
            widthPhysicalMm = widthMm,
            nightModeKey = when (settings.dayNight) {
                DayNight.AUTOMATIC -> "system"
                DayNight.DAY -> "day"
                DayNight.NIGHT -> "night"
                DayNight.SCHEDULE -> "schedule"
            },
            nightStartMinute = settings.nightStartMinute,
            nightEndMinute = settings.nightEndMinute,
            rightHandDrive = settings.rightHandDrive,
            carBluetoothAudio = settings.soundThroughCarBluetooth,
            locationReporting = settings.sendCarLocation,
            bydDirections = settings.bydDirections,
            carHotspot = settings.carHotspot,
            homeWifi = settings.homeWifi,
        )
    }

    /**
     * DiAuto "settings" keys and values. [panelDensityDpi] is the head unit's own density, used to
     * scale Android Auto's interface. Only keys owned by a unified setting are returned.
     */
    fun androidAuto(settings: UnifiedSettings, path: WirelessPath?, panelDensityDpi: Int,
                    wifi: WifiEnvironment? = null): Map<String, Any> = buildMap {
        when (settings.picture) {
            Picture.AUTOMATIC -> { put("resolutionId", 0); put("fps-limit", 60) }
            Picture.BEST -> { put("resolutionId", 3); put("fps-limit", 60) }
            Picture.BALANCED -> { put("resolutionId", 2); put("fps-limit", 30) }
            Picture.LIGHT -> { put("resolutionId", 1); put("fps-limit", 30) }
            Picture.CUSTOM -> Unit
        }
        when (settings.interfaceSize) {
            // Lower DPI draws Android Auto's controls smaller, matching CarPlay's wider panel.
            InterfaceSize.SMALL -> put("dpi-pixel-density", (panelDensityDpi * 0.85f).roundToInt())
            InterfaceSize.MEDIUM -> put("dpi-pixel-density", 0)
            InterfaceSize.LARGE -> put("dpi-pixel-density", (panelDensityDpi * 1.2f).roundToInt())
            InterfaceSize.CUSTOM -> Unit
        }
        put("night-mode", when (settings.dayNight) {
            DayNight.AUTOMATIC -> 0 // sunrise/sunset
            DayNight.DAY -> 1
            DayNight.NIGHT -> 2
            DayNight.SCHEDULE -> 3
        })
        put("night-mode-manual-start", settings.nightStartMinute)
        put("night-mode-manual-end", settings.nightEndMinute)
        put("right-hand-drive", settings.rightHandDrive)
        put("music-via-bluetooth", settings.soundThroughCarBluetooth)
        put("gps-navigation", settings.sendCarLocation)
        put("byd-navigation-enabled", settings.bydDirections)
        // Native wireless Android Auto: 1 = an access point the phone joins, 0 = Wi-Fi Direct.
        // Same Wi-Fi uses DiAuto's access-point route with every value named by hand: the car's
        // Wi-Fi interface, the router's name, password and BSSID. The hotspot route clears them.
        when (path) {
            WirelessPath.SAME_WIFI -> if (wifi?.joinedInterface != null && wifi.joinedBssid != null) {
                put("native-ap-transport", 1)
                put("hotspot-interface", wifi.joinedInterface)
                put("hotspot-ssid", settings.homeWifi.ssid)
                put("hotspot-password", settings.homeWifi.password)
                put("static-bssid", wifi.joinedBssid)
                put("auto-enable-hotspot", false)
            }
            // No session path (a settings change): leave the wireless route of a running session alone.
            null -> Unit
            WirelessPath.CAR_HOTSPOT, WirelessPath.WIFI_DIRECT -> {
                put("native-ap-transport", if (path == WirelessPath.CAR_HOTSPOT) 1 else 0)
                put("hotspot-interface", "")
                put("static-bssid", "0")
                put("hotspot-ssid", if (settings.carHotspot.isValid()) settings.carHotspot.ssid else "")
                put("hotspot-password", if (settings.carHotspot.isValid()) settings.carHotspot.password else "")
            }
        }
    }
}
