package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayVideoLayout
import kotlin.math.roundToInt

internal enum class CarPlayVideoSurfaceMode { TEXTURE, SURFACE }

/**
 * Decide from the attached window, not the declared manifest flag or vehicle model. Smooth video also
 * needs a SurfaceView: only the compositor honours the frame timestamps it releases with.
 */
internal fun carPlayVideoSurfaceMode(hardwareAccelerated: Boolean, smoothVideo: Boolean = false): CarPlayVideoSurfaceMode =
    if (hardwareAccelerated && !smoothVideo) CarPlayVideoSurfaceMode.TEXTURE else CarPlayVideoSurfaceMode.SURFACE

/** Integer compositor bounds retain overscan when a CarPlay view area crops the canvas. */
internal data class CarPlaySurfaceBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun from(content: CarPlayVideoLayout): CarPlaySurfaceBounds {
            val left = content.left.roundToInt()
            val top = content.top.roundToInt()
            return CarPlaySurfaceBounds(left, top,
                ((content.left + content.width).roundToInt() - left).coerceAtLeast(1),
                ((content.top + content.height).roundToInt() - top).coerceAtLeast(1))
        }
    }
}

/**
 * Smooth video delay over the link's base delay. On my Tang the Qualcomm decoder released a frame only
 * after about two more had been queued (p90 65-80 ms at about 50 fps), so the delay is three frame
 * intervals plus 40 ms: 90 ms at 60 fps, measured; 140 ms at 30 fps, derived, not measured.
 */
internal fun smoothVideoDelayMillis(fps: Int): Int = 40 + 3_000 / fps.coerceIn(30, 60)

/** Texture wrappers are ours to release; SurfaceHolder surfaces belong to the framework. */
internal class CarPlayVideoSurfaceOwner<T : Any>(
    private val detach: (T) -> Unit,
    private val release: (T) -> Unit,
) {
    var current: T? = null
        private set
    private var owned = false

    fun replace(surface: T, releaseOnDetach: Boolean) {
        if (current === surface) return
        clear()
        current = surface
        owned = releaseOnDetach
    }

    /** Ignore a late callback from a surface replaced by a newer output. */
    fun clear(surface: T) {
        if (current === surface) clear()
    }

    fun clear() {
        val previous = current ?: return
        val releasePrevious = owned
        current = null
        owned = false
        try {
            detach(previous)
        } finally {
            if (releasePrevious) release(previous)
        }
    }
}
