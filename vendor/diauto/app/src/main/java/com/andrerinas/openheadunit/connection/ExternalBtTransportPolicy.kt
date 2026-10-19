// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0),
// connection/wifi/modes/nativeaa/ExternalBtTransportPolicy.kt (see docs/VENDOR_CHANGES.md).
// Upstream's inputs are its own settings toggles and a daemon measurement; here the host (CarLink)
// names the route per session, after its own probe and the driver's opt-in, so only route() is kept.
package com.andrerinas.openheadunit.connection

import android.content.Context
import com.andrerinas.openheadunit.HostIntegration
import com.andrerinas.openheadunit.aap.WirelessTimeline
import com.andrerinas.openheadunit.aap.WirelessTiming
import com.andrerinas.openheadunit.connection.blink.BlinkAaCarrier
import com.andrerinas.openheadunit.connection.zbt.ZbtAaCarrier
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings

/** Which Bluetooth route the native wireless handshake (mode 3) takes on this unit. */
object ExternalBtTransportPolicy {

    enum class Route {
        /** This unit's own Bluetooth (android.bluetooth RFCOMM). */
        NORMAL,

        /** The external module, through the vendor daemon `gocsdk_zj` on 127.0.0.1:3152. */
        ZBT,

        /** External Bluetooth and no route through it: refuse mode 3 and say why. */
        BLOCKED,

        /** FYT's external module, through `/dev/auto_serial`. */
        BLINK,
    }

    /**
     * Key in DiAuto's settings file where the host names the module route for the next session:
     * "ZBT", "BLINK", or empty for none. Written by CarLink's AaSessionSettings before every start.
     */
    const val HOST_ROUTE_KEY = "carlink-external-bt-route"

    /**
     * @param externalBtEvidence what ExternalBtPolicy found (BluetoothHelper.externalBtEvidence), or null
     * @param hostRoute the module route the host opted into, or null
     * @param overridden a secondary Bluetooth service configured by hand (externalBtOverridden)
     */
    fun route(externalBtEvidence: String?, hostRoute: Route?, overridden: Boolean): Route = when {
        // The host's opt-in wins even where this engine's own markers cannot see the module (an
        // FYT unit carries none of the ZLink markers ExternalBtPolicy knows).
        hostRoute == Route.ZBT || hostRoute == Route.BLINK -> hostRoute
        externalBtEvidence == null -> Route.NORMAL
        overridden -> Route.NORMAL
        else -> Route.BLOCKED
    }

    /** What the host wrote under [HOST_ROUTE_KEY]; null for none or an unknown value. */
    fun hostRoute(context: Context): Route? =
        when (context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).getString(HOST_ROUTE_KEY, "")) {
            "ZBT" -> Route.ZBT
            "BLINK" -> Route.BLINK
            else -> null
        }

    /** This unit's route for mode 3 right now. */
    fun routeFor(context: Context): Route = route(
        BluetoothHelper.externalBtEvidence,
        hostRoute(context),
        NativeAaHandshakeManager.externalBtOverridden(context),
    )

    /** What a module carrier needs from NativeAaHandshakeManager. */
    class Hooks(
        /** Runs one handshake over the link and closes it when done. */
        val serve: suspend (HandshakeLink) -> Unit,
        val isRunning: () -> Boolean,
        /** The handoff completed and the AA listeners were closed for the session. */
        val isFinishedForSession: () -> Boolean,
        val isSessionConnected: () -> Boolean,
        val isSettling: () -> Boolean,
        val isHandshakeInFlight: () -> Boolean,
        /** Whether the handshake backoff still allows another attempt. */
        val mayServeHandshake: () -> Boolean,
        /** A phone showed up on the module: a fresh chance, so clear the backoff. */
        val onPhoneEvidence: () -> Unit,
    )

    /** The carrier for a module [route]; null for the others. */
    fun carrier(context: Context, route: Route, hooks: Hooks): ExternalModuleCarrier? = when (route) {
        Route.ZBT -> ZbtAaCarrier(hooks)
        Route.BLINK -> BlinkAaCarrier(hooks, context.packageManager)
        else -> null
    }

    /** One step of a module bring-up, in the engine journal and as a WIRELESS_TIMING line. */
    fun step(phase: WirelessTimeline.Phase, detail: String) {
        AppLog.i("NativeAA: [module] ${phase.label}: $detail")
        WirelessTiming.mark(phase, detail)
    }

    /**
     * The module route cannot reach the module (no daemon, the serial port refused, the stock
     * FYT app holds it). The host fails the session with a reason that says to use USB.
     */
    fun reportUnavailable(route: Route, reason: String) {
        AppLog.e("NativeAA: [$route] the stereo's Bluetooth module cannot be reached: $reason")
        HostIntegration.engineEvent(null, HostIntegration.EngineEvent.EXTERNAL_MODULE_UNAVAILABLE,
            "route=$route;reason=${reason.replace(';', ',')}")
    }
}
