package com.carlink.app.ui.theme

import android.content.Context
import com.carlink.app.detect.HeadUnitDetector

/**
 * Which sizing CarLink uses. The same layouts serve both; only the tokens change.
 *
 * - [CAR]: head units and car displays. The spec's car-grade sizes (76dp targets, 24sp floor)
 *   on a canvas scaled from the screen's pixels, because head-unit DPI reports are unreliable.
 * - [HANDHELD]: phones and tablets used as the car display. Material 3 sizes on the device's
 *   own density, so CarLink looks native there.
 */
enum class UiProfile { CAR, HANDHELD }

/** The driver's choice in Settings > Display > Layout. */
enum class UiProfileMode { AUTO, CAR, HANDHELD }

object UiProfileDetector {
    /** Automatic picks [UiProfile.CAR] on a car display ([HeadUnitDetector], shared with the connection backend). */
    fun detect(context: Context, mode: UiProfileMode): UiProfile = when (mode) {
        UiProfileMode.CAR -> UiProfile.CAR
        UiProfileMode.HANDHELD -> UiProfile.HANDHELD
        UiProfileMode.AUTO -> if (looksLikeCarDisplay(context)) UiProfile.CAR else UiProfile.HANDHELD
    }

    fun looksLikeCarDisplay(context: Context): Boolean = HeadUnitDetector.looksLikeCarDisplay(context)
}
