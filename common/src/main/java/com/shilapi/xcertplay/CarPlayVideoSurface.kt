package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayVideoLayout
import kotlin.math.roundToInt

internal enum class CarPlayVideoSurfaceMode { TEXTURE, SURFACE }

/** Decide from the attached window, not the declared manifest flag or vehicle model. */
internal fun carPlayVideoSurfaceMode(hardwareAccelerated: Boolean): CarPlayVideoSurfaceMode =
    if (hardwareAccelerated) CarPlayVideoSurfaceMode.TEXTURE else CarPlayVideoSurfaceMode.SURFACE

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
