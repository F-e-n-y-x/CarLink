package com.shilapi.xcertplay.airplay

/**
 * CarPlay's instrument-cluster screen (stream type 111).
 *
 * The iPhone lists the cluster content it offers in its /info request (`altScreenURLs`); without
 * an initial URL it streams a black frame. The cluster has no input. Apple Maps keeps the car
 * position inside the safe area while the map still fills the panel, so bottom and right insets
 * move the car up and to the left, away from the cluster's own readouts.
 */
object CarPlayClusterDisplay {
    const val MAP_URL = "maps:/car/instrumentcluster/map"

    /** Bottom inset in percent of the height. The default suits the BYD cluster. */
    const val DEFAULT_LIFT_PERCENT = 25
    val liftPresets = listOf(0, DEFAULT_LIFT_PERCENT, 45)

    /** Right inset in percent of the width; the car moves left by half of it (22 % ≈ 1/9). */
    const val DEFAULT_SHIFT_PERCENT = 22
    val shiftPresets = listOf(0, 17, 20, DEFAULT_SHIFT_PERCENT, 25, 33, 67)

    private const val WIDTH_PHYSICAL_MM = 292 // a 12.3-inch 8:3 cluster panel
    private const val FPS = 30

    fun config(widthPixels: Int, heightPixels: Int, liftPercent: Int, shiftPercent: Int): AirPlayDisplayConfig {
        val lift = heightPixels * liftPercent.coerceIn(0, 90) / 100
        val shift = widthPixels * shiftPercent.coerceIn(0, 90) / 100
        return AirPlayDisplayConfig(
            widthPixels = widthPixels,
            heightPixels = heightPixels,
            widthPhysicalMm = WIDTH_PHYSICAL_MM,
            heightPhysicalMm = Math.round(WIDTH_PHYSICAL_MM * heightPixels.toDouble() / widthPixels).toInt(),
            fps = FPS,
            primaryInputDevice = 0,
            features = 0,
            initialUrl = MAP_URL,
            safeArea = if (lift > 0 || shift > 0) AirPlayInsets(bottom = lift, right = shift) else null,
            safeAreaDrawOutside = true,
        )
    }
}
