package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings

/**
 * Whether the user's chosen connection types allow any wireless transport to run.
 *
 * [Settings.connectionModes] already hides the Wireless Connection settings when WiFi is not
 * chosen, but [Settings.wifiConnectionMode] keeps its stored value (Native AA by default). Without
 * this gate a USB-only user still got a P2P group or hotspot route plus the Bluetooth handshake,
 * and the phone switched to wireless as soon as it came into range — with the setting that would
 * turn it off hidden from view.
 *
 * Self mode is unaffected: it starts its own loopback server directly, not through
 * [AapService.initWifiMode].
 */
object WirelessScopePolicy {

    /** Mode 0 (Manual) starts no server, discovery, P2P group, hotspot route or handshake. */
    const val NO_WIRELESS_MODE = 0

    /** An empty selection means nothing was chosen yet, which shows (and allows) everything. */
    fun allowsWireless(connectionModes: Set<Settings.ConnectionMode>): Boolean =
        connectionModes.isEmpty() || Settings.ConnectionMode.WIFI in connectionModes

    /** The wireless mode to actually bring up for the stored [storedMode] and chosen types. */
    fun effectiveMode(storedMode: Int, connectionModes: Set<Settings.ConnectionMode>): Int =
        if (allowsWireless(connectionModes)) storedMode else NO_WIRELESS_MODE
}
