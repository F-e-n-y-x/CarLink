// CarLink: new file (docs/VENDOR_CHANGES.md, DiAuto, live surface swap).
package com.andrerinas.openheadunit.decoder

/**
 * Whether a new projection surface can be handed to the running decoder with
 * `MediaCodec.setOutputSurface` instead of stopping and rebuilding it.
 *
 * The swap keeps the codec's reference frames, so the next P-frame draws a correct picture on the
 * new surface. A rebuilt codec has none and shows black or smears until the phone's next keyframe,
 * which can be a minute away. Only a healthy hardware codec qualifies: the bundled software HEVC
 * decoder renders through its own path, and a latched failure or a pending restart is cleared only
 * by the rebuild. A swap that throws falls back to the rebuild as well.
 */
object SurfaceSwapPolicy {
    fun canSwap(
        hasHardwareCodec: Boolean,
        running: Boolean,
        softwareHevc: Boolean,
        permanentlyFailed: Boolean,
        restartPending: Boolean,
        newSurfaceValid: Boolean,
    ): Boolean = hasHardwareCodec && running && !softwareHevc && !permanentlyFailed &&
        !restartPending && newSurfaceValid
}
