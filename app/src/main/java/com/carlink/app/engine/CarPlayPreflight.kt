package com.carlink.app.engine

import android.content.Context
import com.carlink.app.core.LinkProblem
import com.carlink.app.core.Transport
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostIntegration
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * The checks DiPlay's own Connect button ran before opening the CarPlay picture
 * (DiPlayActivity.connect), done by CarLink so DiPlay's screens never appear. The CarPlay host
 * itself still asks for runtime permissions and VPN consent.
 */
internal object CarPlayPreflight {
    fun check(context: Context, transport: Transport, iphoneAddress: String?): LinkProblem? {
        if (CarPlayHostIntegration.authenticationError(context) != null) return LinkProblem.CARPLAY_AUTH_MISSING
        if (transport == Transport.USB) return null
        if (iphoneAddress == null && CarPlayHostIntegration.selectedPhoneAddress(context) == null) {
            return LinkProblem.NO_IPHONE_SELECTED
        }
        return when (AirPlayPersistence.loadWirelessHotspotMode(context)) {
            WirelessHotspotMode.MANUAL -> when {
                ManualHotspotValidation.error(
                    AirPlayPersistence.loadManualHotspotSsid(context),
                    AirPlayPersistence.loadManualHotspotPassphrase(context),
                ) != null -> LinkProblem.CAR_HOTSPOT_DETAILS_MISSING
                // Off and CarLink cannot switch it on itself (BYD ADB automation).
                CarHotspotStatus.isEnabled(context) == false &&
                    !(CarHotspotSettings.enabled(context) && CarHotspotTethering.permitted(context)) ->
                    LinkProblem.CAR_HOTSPOT_OFF
                else -> null
            }
            WirelessHotspotMode.EXISTING_WIFI -> if (ManualHotspotValidation.error(
                    AirPlayPersistence.loadExistingWifiSsid(context),
                    AirPlayPersistence.loadExistingWifiPassphrase(context),
                ) != null) LinkProblem.SAME_WIFI_DETAILS_MISSING else null
            else -> null
        }
    }
}
