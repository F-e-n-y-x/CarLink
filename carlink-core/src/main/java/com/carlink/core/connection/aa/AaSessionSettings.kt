package com.carlink.core.connection.aa

import android.content.Context
import android.os.Bundle
import com.andrerinas.openheadunit.connection.ExternalBtTransportPolicy
import com.carlink.core.connection.CarPlaySettingsPolicy
import com.carlink.core.connection.PictureChoice
import com.carlink.core.connection.PicturePolicy
import com.carlink.core.core.Transport
import com.carlink.core.engine.AndroidAutoSettings
import com.carlink.core.settings.AudioLevels
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.Picture
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.VehicleProfile
import com.carlink.core.settings.WheelKeys
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
    private const val BACK = android.view.KeyEvent.KEYCODE_BACK

    /**
     * @param panelDensityDpi the head unit's own density, which scales Android Auto's interface.
     * @param wifi the car's Wi-Fi right now; Same Wi-Fi needs its client interface and the router's BSSID.
     * @param moduleRoute the stereo's Bluetooth module route the driver opted into (DeviceEnvironment.moduleRoute).
     * @param bydHeadUnit this screen is a BYD DiLink head unit ([VehicleProfile.detect]), not just set to the BYD brand.
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
        moduleRoute: ExternalBtTransportPolicy.Route? = null,
        bydHeadUnit: Boolean = profile == VehicleProfile.BYD_DILINK,
        audioStreams: Map<String, Int> = emptyMap(),
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
            // Only on a real DiLink head unit ([bydHeadUnit], VehicleProfile.detect): a phone or tablet whose
            // car brand is set to BYD kept its music on the phone (tablet, 2026-11-18).
            put("music-via-bluetooth", (profile == VehicleProfile.BYD_DILINK && bydHeadUnit) || settings.soundThroughCarBluetooth)
            // BYD's HUD/cluster outputs only on a BYD (DA§12.2).
            put("byd-navigation-enabled", profile == VehicleProfile.BYD_DILINK && settings.bydDirections)
            // DiAuto 0.3.12's BYD dashboard map (a second video stream announced to the phone and a
            // window on the cluster display). Off everywhere: CarLink has no setting for it yet,
            // and it is untested beyond DiLink 5.1. A BYD-only setting can turn it on later.
            put("cluster-map-enabled", false)
            put(com.carlink.core.detect.ScreenType.KEY, com.carlink.core.detect.ScreenType.encode())
            putPicture(settings, panelDensityDpi, picture)
            // 0 = follow the screen's rotation. The picture size is agreed once per session, so a
            // turned screen reports ORIENTATION_CHANGED and SessionManager reconnects (issue #31).
            put("screen-orientation", 0)
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
            // The learned steering-wheel buttons, in DiAuto's own keymap (logical-physical), which
            // CommManager.sendKey applies to every key route. CarLink owns the keymap: always written.
            // Back mapped to itself makes AapProjectionActivity send it to the phone instead of opening
            // its exit menu (ProjectionKeyPolicy, #72); the two-finger edge swipe still opens the menu.
            put("key-codes", WheelKeys.diAutoKeyCodes(settings.wheelKeys) +
                if (settings.androidAutoBack) setOf("$BACK-$BACK") else emptySet())
            // Sound levels as DiAuto's own offsets. Android Auto's speech channel (DiAuto's "assistant")
            // carries the spoken directions as well as the Assistant, and its system channel (DiAuto's
            // "navigation") the prompts' chimes, so both follow the navigation level. Calls are not
            // Android Auto audio (they go over Bluetooth hands-free), so the call level has nothing here.
            // Cuts only: DiAuto clips a boost flat and stops at +6 dB, so boosts go through CarLink's
            // playback hook (AaEngineHooks, AudioLevels.scalePcm16's soft clip), up to +12 dB for directions.
            put("media-volume-offset", AudioLevels.aaOffsetPercent(settings.mediaLevelDb.coerceAtMost(0)))
            put("assistant-volume-offset", AudioLevels.aaOffsetPercent(settings.navigationLevelDb.coerceAtMost(0)))
            put("navigation-volume-offset", AudioLevels.aaOffsetPercent(settings.navigationLevelDb.coerceAtMost(0)))
            put(MEDIA_BOOST_KEY, settings.mediaLevelDb.coerceAtLeast(0))
            put(NAVIGATION_BOOST_KEY, settings.navigationLevelDb.coerceAtLeast(0))
            // A stereo that sorts sources by stream type (Joying, QzdHeadUnit.audioStreams): directions and the
            // Assistant on its navigation and voice streams through DiAuto's car volume groups. Elsewhere untouched.
            audioStreams["NAVIGATION"]?.let {
                put("car-volume-groups", true)
                put("car-navi-stream", it)
                put("car-voice-stream", audioStreams["ASSISTANT"] ?: -1)
            }
            put(MIC_GAIN_KEY, settings.micGainDb)
            put(RESUME_MUSIC_KEY, settings.resumeMusic)
            // The engine's Bluetooth route for mode 3 (design/EXTERNAL_BLUETOOTH.md). A wireless
            // session runs only where CarLink's pre-flight allowed it, so without a module route it
            // is NORMAL: DiAuto's own markers must not refuse a stereo CarLink judged usable
            // (LIKELY_FALSE_POSITIVE, or the driver chose this screen's Bluetooth).
            put(ExternalBtTransportPolicy.HOST_ROUTE_KEY,
                if (transport == Transport.WIRELESS) (moduleRoute ?: ExternalBtTransportPolicy.Route.NORMAL).name else "")

            // The wireless route. A USB session writes the Wi-Fi Direct route: the engine arms
            // wireless in the background of every service start (DA§2.3), and must not do so with
            // an earlier session's shared network and BSSID.
            val route = if (transport == Transport.USB) WirelessPath.WIFI_DIRECT else path ?: WirelessPath.WIFI_DIRECT
            when (route) {
                WirelessPath.HEADUNIT_SERVER -> {
                    // DiAuto's mode 1: its NetworkDiscovery dials the phone's head unit server on
                    // 5277, the Wi-Fi gateway first (the phone, on its own hotspot), then the subnet.
                    put("wifi-connection-mode", 1)
                    put("native-ap-transport", 0)
                    put("hotspot-interface", "")
                    put("hotspot-ssid", "")
                    put("hotspot-password", "")
                    put("static-bssid", "0")
                    put("auto-enable-hotspot", false)
                }
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
                if (picture.aaSurfaceView == true) put("view-mode", 0) // SurfaceView: no GPU pass per frame
            }
            Picture.BEST -> { put("resolutionId", 3); put("fps-limit", 60) }
            Picture.BALANCED -> { put("resolutionId", 2); put("fps-limit", 30) }
            Picture.LIGHT -> { put("resolutionId", 1); put("fps-limit", 30) }
            Picture.CUSTOM -> Unit // Advanced owns the exact values.
        }
        // Small and Large scale the Automatic density, which already fits this screen: scaling the
        // panel's own density made Large 2.4x Medium on a 320 dpi tablet whose Automatic is 160.
        put(SIZE_PERCENT_KEY, if (settings.interfaceSize == InterfaceSize.CUSTOM) settings.customSizePercent
            else sizePercent(settings.interfaceSize))
        if (settings.interfaceSize != InterfaceSize.CUSTOM) put("dpi-pixel-density", 0)
    }

    /** Interface size as a percentage of the Automatic density (Custom: Advanced owns the DPI). */
    fun sizePercent(size: InterfaceSize): Int = when (size) {
        // One step up since 2026-11-18 (Small is the old Medium), matching CarPlay's widths.
        InterfaceSize.SMALL, InterfaceSize.CUSTOM -> 100
        InterfaceSize.MEDIUM -> 135
        InterfaceSize.LARGE -> 165
        InterfaceSize.EXTRA_LARGE -> 200 // the 748x450 dp floor still caps it on a head unit
    }

    /**
     * The density DiAuto announces for "dpi-pixel-density" 0 (Interface size Medium, or Advanced DPI
     * Automatic): the device's own density, lowered where it would leave Android Auto less than its
     * minimum 748x450 dp canvas, below which it draws a reduced layout without media controls
     * (open-headunit #1101/#1108). [visibleWidthPx] x [visibleHeightPx] is the codec area less its
     * margins. Portrait is left alone: the floor is a landscape one. A typed DPI never comes here.
     * A [handheld] screen (phone, tablet) starts from a head unit's 160 dpi instead of its own: at a
     * phone's 300+ dpi Android Auto drew a phone-sized layout, while CarPlay on the same screen is laid
     * out for a car-sized display (2026-11-10, Galaxy S25 as the screen). [sizePercent] is the
     * driver's interface size (Small 100 to Extra large 200; steps under 15 % were too close to tell apart); the 748x450 dp floor still caps it.
     */
    fun automaticDensityDpi(deviceDpi: Int, visibleWidthPx: Int, visibleHeightPx: Int, handheld: Boolean = false,
                            sizePercent: Int = 100): Int {
        val base = ((if (handheld) handheldDpi(deviceDpi, visibleWidthPx, visibleHeightPx) else deviceDpi) * sizePercent / 100f).roundToInt()
        if (visibleWidthPx <= 0 || visibleHeightPx <= 0 || visibleHeightPx > visibleWidthPx) return base
        return minOf(base, visibleWidthPx * 160 / MIN_WIDTH_DP, visibleHeightPx * 160 / MIN_HEIGHT_DP)
    }

    private const val HANDHELD_DPI = 160

    /**
     * A phone or tablet as the screen gets CarPlay's own size rule (CarPlaySettings: Medium tells the
     * iPhone the screen's long side is 300 mm), so both projections draw the same size: a fixed 160 dpi
     * left Android Auto about 1.4x smaller than CarPlay on a 2560x1600 tablet (2026-11-12). Never above
     * the screen's own density; 160 when the size is unknown.
     */
    private fun handheldDpi(deviceDpi: Int, w: Int, h: Int): Int {
        val longSide = maxOf(w, h)
        if (longSide <= 0) return minOf(deviceDpi, HANDHELD_DPI)
        return minOf(deviceDpi, (longSide * MM_PER_INCH / HANDHELD_MEDIUM_WIDTH_MM).roundToInt())
    }

    /**
     * CarPlay's 300 mm Medium measured against Android Auto side by side on the tablet: CarPlay's dock and
     * labels still came out about 1.2x Android Auto's at 300 mm, so Android Auto is laid out for 250 mm.
     */
    private const val HANDHELD_MEDIUM_WIDTH_MM = 250f

    private const val MM_PER_INCH = 25.4f

    /** DiAuto preferences read by CarLink's hooks in ":aa" (AaEngineHooks): microphone gain in dB, resume music. */
    const val MIC_GAIN_KEY = "carlink-mic-gain-db"
    const val MEDIA_BOOST_KEY = "carlink-media-boost-db"
    const val NAVIGATION_BOOST_KEY = "carlink-navigation-boost-db"
    const val RESUME_MUSIC_KEY = "carlink-resume-music"

    /**
     * The largest interface size (percent of Small) Android Auto can show on a turning screen of
     * [widthPx] x [heightPx] held as it is now: sizes zoom into a smaller region, which DiAuto keeps at
     * 748x450 dp or more (HeadUnitScreenConfig, "size capped"). Upright on a tablet that is about 130 %.
     */
    fun turningMaxPercent(deviceDpi: Int, widthPx: Int, heightPx: Int): Int {
        if (widthPx <= 0 || heightPx <= 0) return Int.MAX_VALUE
        val dpi = automaticDensityDpi(deviceDpi, widthPx, heightPx, handheld = true, sizePercent = 100).coerceAtLeast(1)
        return (100f * minOf(widthPx * 160f / (dpi * MIN_WIDTH_DP), heightPx * 160f / (dpi * MIN_HEIGHT_DP))).toInt()
    }

    /** The sizes that show larger than the one before them when Android Auto allows at most [maxPercent]. */
    /** The switcher's Custom size range for Android Auto, percent of the Automatic density. */
    val CUSTOM_PERCENT_RANGE = 80..200

    /**
     * The Android Auto percent that looks like CarPlay at [widthMm] (and back), interpolated between the
     * presets' pairs, so a custom size set for one engine gives the other the same size.
     */
    fun percentForCarPlayWidth(widthMm: Int): Int =
        interpolate(widthMm.toFloat(), sizePairs.map { it.first.toFloat() to it.second.toFloat() }).roundToInt()
            .coerceIn(CUSTOM_PERCENT_RANGE)

    fun carPlayWidthForPercent(percent: Int): Int =
        (interpolate(percent.toFloat(), sizePairs.map { it.second.toFloat() to it.first.toFloat() }) / 5f).roundToInt() * 5

    private val sizePairs get() = listOf(InterfaceSize.SMALL, InterfaceSize.MEDIUM, InterfaceSize.LARGE, InterfaceSize.EXTRA_LARGE)
        .map { CarPlaySettingsPolicy.widthMm(it)!! to sizePercent(it) }

    /** Piecewise linear through [points] (x ascending or descending), extended along the end segments. */
    private fun interpolate(x: Float, points: List<Pair<Float, Float>>): Float {
        val sorted = points.sortedBy { it.first }
        val i = sorted.indexOfFirst { it.first >= x }.let { if (it <= 0) 1 else it }.coerceAtMost(sorted.size - 1)
        val (x0, y0) = sorted[i - 1]
        val (x1, y1) = sorted[i]
        return y0 + (x - x0) * (y1 - y0) / (x1 - x0)
    }

    fun sizesThatGrow(maxPercent: Int): List<InterfaceSize> {
        val steps = listOf(InterfaceSize.SMALL, InterfaceSize.MEDIUM, InterfaceSize.LARGE, InterfaceSize.EXTRA_LARGE)
        return steps.filterIndexed { i, _ -> i == 0 || sizePercent(steps[i - 1]) < maxPercent }
    }

    /** DiAuto preference with [sizePercent], read by the Automatic density hook in ":aa". */
    const val SIZE_PERCENT_KEY = "carlink-interface-percent"

    private const val MIN_WIDTH_DP = 748
    private const val MIN_HEIGHT_DP = 450

    /** Writes [values] into DiAuto's settings in ":aa". Blocking IPC: call off the main thread. */
    fun apply(context: Context, values: AaSessionValues) {
        val bundle = Bundle()
        values.values.forEach { (key, value) ->
            when (value) {
                is Int -> bundle.putInt(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is String -> bundle.putString(key, value)
                // The provider stores an ArrayList as a string set (connection-modes, key-codes).
                is Set<*> -> bundle.putStringArrayList(key, ArrayList(value.map { it.toString() }))
            }
        }
        AndroidAutoSettings.write(context, bundle)
    }
}
