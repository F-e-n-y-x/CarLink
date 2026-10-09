package com.shilapi.xcertplay.hud

/**
 * CarLink: one process-wide switch for the BYD vehicle integrations that DiPlay runs on every
 * head unit without a setting of their own (docs/dissection/DIPLAY.md §12, the ⚠ rows):
 * the navigation output bridges ([BydNavigationOutputs.start], `onAppOpened`, `endNow`), BYD's
 * steering-wheel voice key codes (`CarPlayMediaButton.opensSiri`), the local-adb Wi-Fi scan pause
 * (`WifiScanPause.eligible`) and the adb dashboard-cluster activity (`AdbClusterRouter.enabled`).
 *
 * True by default, so standalone DiPlay behaves as upstream. A host for generic cars sets it to
 * false before the first CarPlay session (`CarPlayHostIntegration.vehicleIntegrations`).
 */
object VehicleIntegrations {
    @Volatile
    @JvmStatic
    var enabled: Boolean = true
}
