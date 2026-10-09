package com.shilapi.xcertplay

import android.Manifest
import android.os.Build
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Runtime permissions a wireless CarPlay start needs per Android release. Android 17 drops local
 * IPv4 traffic for apps targeting API 37 until [Manifest.permission.ACCESS_LOCAL_NETWORK] is
 * granted, which also covers the inbound AirPlay listener; link-local IPv6 keeps working without
 * it, so affected units hang at the preparing screen instead of failing loudly.
 */
internal object WirelessPermissions {
    fun required(hotspotMode: WirelessHotspotMode, sdkInt: Int): List<String> = when {
        hotspotMode == WirelessHotspotMode.EXISTING_WIFI ->
            if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        sdkInt >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }.let { permissions ->
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
            permissions + Manifest.permission.ACCESS_LOCAL_NETWORK
        } else {
            permissions
        }
    }
}
