package com.carlink.app.settings

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.hud.BydOutputSettings

/**
 * Which car-specific integrations the engines may use (design/REBUILD_V2.md §3). Both engines
 * were written for BYD DiLink head units; everything BYD-specific stays off unless the profile is
 * [BYD_DILINK] (docs/dissection/DIPLAY.md §12).
 */
enum class VehicleProfile {
    /** Any car, tablet or phone. The default. */
    GENERIC,

    /** A BYD DiLink head unit: HUD/cluster outputs, BYD wheel keys and adb helpers allowed. */
    BYD_DILINK,
    ;

    companion object {
        /**
         * BYD when DiPlay's own detection finds BYD packages or build identifiers
         * (`BydOutputSettings.available`, which covers the `BYD-AUTO/` fingerprint and the
         * Qualcomm builds that do not say "BYD" in MANUFACTURER; DIPLAY.md §12).
         */
        fun detect(context: Context): VehicleProfile =
            if (runCatching { BydOutputSettings.available(context) }.getOrDefault(false) ||
                Build.FINGERPRINT.orEmpty().startsWith("BYD-AUTO/")) BYD_DILINK else GENERIC
    }
}
