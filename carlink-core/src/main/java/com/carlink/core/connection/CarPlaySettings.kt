package com.carlink.core.connection

import android.content.Context
import com.carlink.core.settings.DayNight
import com.carlink.core.settings.InterfaceSize
import com.carlink.core.settings.Picture
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.VehicleProfile
import com.carlink.core.settings.WheelKeys
import com.carlink.core.settings.WifiDetails
import com.carlink.core.settings.WirelessPath
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppAppearance
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.CarPlayNightMode
import com.shilapi.xcertplay.CarPlayNightSchedule
import com.shilapi.xcertplay.hud.BydOemClusterHold
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Everything CarLink writes into DiPlay for one session (or, with no path, after a settings
 * change). Null means "leave DiPlay's value alone". Built by [CarPlaySettingsPolicy.map], written
 * by [CarPlaySettingsPolicy.apply]. DiPlay reads all of it from its preferences when the host
 * opens (DIPLAY.md §6: "every connection setting is read from SharedPreferences").
 */
data class CarPlayEngineSettings(
    /** DiPlay's Wi-Fi backend for [WirelessPath]; null without a session path. */
    val wirelessMode: WirelessHotspotMode?,
    val displayScalePercent: Int?,
    val fps: Int?,
    /** DiPlay's HEVC switch; false only when Automatic picture found no hardware HEVC decoder ([PicturePolicy]). */
    val hevc: Boolean? = null,
    val widthPhysicalMm: Int?,
    /** DiPlay's night-mode key: system, day, night or schedule. */
    val nightModeKey: String,
    val nightStartMinute: Int,
    val nightEndMinute: Int,
    /** DiPlay's `app_appearance` key (dark, light or auto): its own screens follow CarLink's theme. */
    val appearanceKey: String = AppAppearance.DARK.key,
    val rightHandDrive: Boolean,
    val carBluetoothAudio: Boolean,
    /** iAP2 location; only with the permission, so the host never asks before a start (F17). */
    val locationReporting: Boolean,
    /** Mirrored as they are, so DiPlay never keeps details CarLink no longer has (F17). */
    val carHotspot: WifiDetails,
    val homeWifi: WifiDetails,
    val vehicle: CarPlayVehicleSettings,
    /** Learned steering-wheel buttons, physical key code → standard key code ([WheelKeys.remap]), for the CarPlay screen. */
    val wheelKeyRemap: Map<Int, Int> = emptyMap(),
    /** DiPlay answers and ends CarPlay calls with the call keys (`BydOutputSettings.carPlayCallControls`). */
    val callKeys: Boolean = false,
)

/** The vehicle-profile part of [CarPlayEngineSettings] (DIPLAY.md §12, F5). */
data class CarPlayVehicleSettings(
    /** `CarPlayHostIntegration.vehicleIntegrations`: BYD bridges, wheel codes, adb helpers. */
    val integrations: Boolean,
    /** BYD HUD/cluster turn-by-turn output (`BydOutputSettings.navigation_enabled`, default on in DiPlay). */
    val navigationOutput: Boolean,
    /**
     * Switch off every other BYD output and dashboard feature (battery, wheel speed, video while
     * parked, cluster/HUD song, calls, OEM cluster hold, cluster map, centre map, adb cluster,
     * car-hotspot adb auto-start). False leaves the driver's own choices (BYD profile).
     */
    val forceBydExtrasOff: Boolean,
    /** The car brand the iPhone shows (DiPlay's `oem_label`, which defaults to "BYD"). */
    val oemLabel: String,
    /** Saved labels that are a default, not a driver's choice, and may be replaced by [oemLabel]. */
    val replaceableOemLabels: Set<String>,
)

/**
 * CarLink's colour theme. The app owns it (its own screen preferences), so it reaches the backend
 * through [CarPlaySettingsPolicy.appearance].
 */
enum class CarLinkAppearance { FOLLOW_SYSTEM, DARK, LIGHT }

/** Pure translation from CarLink's settings to DiPlay's, so every rule is unit tested. */
object CarPlaySettingsPolicy {
    /**
     * CarLink's theme at the time of a write; the app sets it at start-up. The default, dark,
     * is DiPlay's own default, so a host that never sets it changes nothing.
     */
    @Volatile
    var appearance: () -> CarLinkAppearance = { CarLinkAppearance.DARK }

    /** DiPlay's own default OEM label (AirPlayPersistence.DEFAULT_OEM_LABEL, DIPLAY.md §12 row 15). */
    const val DIPLAY_DEFAULT_OEM_LABEL = "BYD"

    fun map(
        settings: UnifiedSettings,
        path: WirelessPath?,
        profile: VehicleProfile,
        fineLocationGranted: Boolean,
        brandName: String = "CarLink",
        picture: PictureChoice = PicturePolicy.ENGINE_DEFAULTS,
        appearance: CarLinkAppearance = runCatching { this.appearance() }.getOrDefault(CarLinkAppearance.DARK),
    ): CarPlayEngineSettings {
        // Picture and size: v1's settings mapping (verified correct, F17); Automatic follows the
        // device's decoder ([PicturePolicy]: DiPlay's own 100 % / 30 fps unless the decoder needs less).
        val (scale, fps) = when (settings.picture) {
            Picture.AUTOMATIC -> picture.carPlayScalePercent to picture.carPlayFps
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
        val byd = profile == VehicleProfile.BYD_DILINK
        return CarPlayEngineSettings(
            wirelessMode = when (path) {
                WirelessPath.SAME_WIFI -> WirelessHotspotMode.EXISTING_WIFI
                WirelessPath.CAR_HOTSPOT -> WirelessHotspotMode.MANUAL
                WirelessPath.WIFI_DIRECT -> WirelessHotspotMode.WIFI_P2P
                null -> null
            },
            displayScalePercent = scale,
            fps = fps,
            hevc = if (settings.picture == Picture.AUTOMATIC) picture.carPlayHevc else null,
            widthPhysicalMm = widthMm,
            nightModeKey = when (settings.dayNight) {
                DayNight.AUTOMATIC -> "system"
                DayNight.DAY -> "day"
                DayNight.NIGHT -> "night"
                DayNight.SCHEDULE -> "schedule"
            },
            nightStartMinute = settings.nightStartMinute,
            nightEndMinute = settings.nightEndMinute,
            // DiPlay's chrome (the CarPlay menu, waiting screen and panels) in CarLink's theme.
            // "Auto" follows the car's day/night as CarPlay does: the live CarPlay night mode,
            // else the night mode above, which is the system's under CarLink's Automatic.
            appearanceKey = when (appearance) {
                CarLinkAppearance.FOLLOW_SYSTEM -> AppAppearance.AUTO.key
                CarLinkAppearance.DARK -> AppAppearance.DARK.key
                CarLinkAppearance.LIGHT -> AppAppearance.LIGHT.key
            },
            rightHandDrive = settings.rightHandDrive,
            carBluetoothAudio = settings.soundThroughCarBluetooth,
            // F17: CarLink defaults this on, DiPlay off; without the permission the host would ask
            // before every start and save false, and CarLink would write true again.
            locationReporting = settings.sendCarLocation && fineLocationGranted,
            carHotspot = settings.carHotspot,
            homeWifi = settings.homeWifi,
            vehicle = CarPlayVehicleSettings(
                integrations = byd,
                // F5: DiPlay and UnifiedSettings both default the BYD output on; only BYD keeps it.
                navigationOutput = byd && settings.bydDirections,
                forceBydExtrasOff = !byd,
                oemLabel = if (byd) DIPLAY_DEFAULT_OEM_LABEL else brandName,
                replaceableOemLabels = setOf(DIPLAY_DEFAULT_OEM_LABEL, brandName, ""),
            ),
            wheelKeyRemap = WheelKeys.remap(settings.wheelKeys),
            // Only CarLink sets it (no CarLink screen shows DiPlay's switch): on exactly when a call key is learned.
            callKeys = WheelKeys.needsCarPlayCallKeys(settings.wheelKeys),
        )
    }

    /**
     * Writes [values] into DiPlay's preferences and the process-wide host hooks, before the host
     * opens. Call off the main thread: some of DiPlay's setters commit synchronously.
     */
    fun apply(context: Context, values: CarPlayEngineSettings) {
        values.wirelessMode?.let { AirPlayPersistence.saveWirelessHotspotMode(context, it) }
        values.displayScalePercent?.let { AirPlayPersistence.saveDisplayScalePercent(context, it) }
        values.fps?.let { AirPlayPersistence.saveFps(context, it) }
        values.hevc?.let { AirPlayPersistence.saveHevcEnabled(context, it) }
        values.widthPhysicalMm?.let { AirPlayPersistence.saveWidthPhysicalMm(context, it) }
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.fromKey(values.nightModeKey))
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.fromKey(values.appearanceKey))
        runCatching {
            AirPlayPersistence.saveCarPlayNightSchedule(context, CarPlayNightSchedule(values.nightStartMinute, values.nightEndMinute))
        }
        AirPlayPersistence.saveRightHandDrive(context, values.rightHandDrive)
        AirPlayPersistence.saveCarBluetoothAudio(context, values.carBluetoothAudio)
        AirPlayPersistence.saveLocationReportingEnabled(context, values.locationReporting)

        // Car hotspot: name, password and the security they imply. The band and channel are the
        // driver's (DiPlay menu) and are no longer forced to AUTO on every write (F17).
        AirPlayPersistence.saveManualHotspotSsid(context, values.carHotspot.ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(context, values.carHotspot.password)
        AirPlayPersistence.saveManualHotspotSecurity(context, ManualHotspotValidation.securityFor(values.carHotspot.password))
        // Same Wi-Fi: always CarLink's values, also when cleared (F17: stale details).
        AirPlayPersistence.saveExistingWifiCredentials(context, values.homeWifi.ssid, values.homeWifi.password)

        CarPlayHostIntegration.keyRemap = values.wheelKeyRemap
        if (BydOutputSettings.carPlayCallControls(context) != values.callKeys) {
            BydOutputSettings.setCarPlayCallControls(context, values.callKeys)
        }

        val vehicle = values.vehicle
        CarPlayHostIntegration.vehicleIntegrations = vehicle.integrations
        BydOutputSettings.setEnabled(context, vehicle.navigationOutput)
        if (vehicle.forceBydExtrasOff) {
            // Written only when on: several of DiPlay's setters commit synchronously.
            fun off(isOn: (Context) -> Boolean, set: (Context, Boolean) -> Unit) { if (isOn(context)) set(context, false) }
            off(BydOutputSettings::batteryToIphone, BydOutputSettings::setBatteryToIphone)
            off(BydOutputSettings::wheelSpeedToIphone, BydOutputSettings::setWheelSpeedToIphone)
            off(BydOutputSettings::videoWhileParked, BydOutputSettings::setVideoWhileParked)
            off(BydOutputSettings::clusterSong, BydOutputSettings::setClusterSong)
            off(BydOutputSettings::hudSong, BydOutputSettings::setHudSong)
            off(BydOutputSettings::carPlayCalls, BydOutputSettings::setCarPlayCalls)
            off(AirPlayPersistence::loadClusterMapEnabled, AirPlayPersistence::saveClusterMapEnabled)
            off(AirPlayPersistence::loadCenterMapOverlay, AirPlayPersistence::saveCenterMapOverlay)
            off(AirPlayPersistence::loadAdbClusterEnabled, AirPlayPersistence::saveAdbClusterEnabled)
            off(CarHotspotSettings::enabled, CarHotspotSettings::setEnabled)
            if (BydOutputSettings.oemClusterHold(context) != BydOemClusterHold.OFF) {
                BydOutputSettings.setOemClusterHold(context, BydOemClusterHold.OFF)
            }
        }
        // Replace only a default label, so a name the driver typed in the CarPlay menu stays.
        if (AirPlayPersistence.loadOemLabel(context) in vehicle.replaceableOemLabels) {
            AirPlayPersistence.saveOemLabel(context, vehicle.oemLabel)
        }
    }
}
