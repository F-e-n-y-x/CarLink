package com.carlink.app.ui.theme

import android.content.Context
import com.carlink.core.detect.HeadUnitDetector

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

    /**
     * Decided once per process: Android Auto's engine turns the system's car mode on while it runs
     * (DiAuto AapService.setupCarMode), which flipped a tablet's switcher panel and Home to the car
     * layout during Android Auto only, so the two projections looked different (tablet, 2026-11-12).
     */
    fun looksLikeCarDisplay(context: Context): Boolean =
        carDisplay ?: HeadUnitDetector.looksLikeCarDisplay(context).also { carDisplay = it }

    @Volatile private var carDisplay: Boolean? = null
}
