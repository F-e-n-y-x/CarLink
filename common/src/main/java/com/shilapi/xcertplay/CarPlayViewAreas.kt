package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.abs
import kotlin.math.ln

/**
 * The view areas the main screen declares for one session, and the one CarPlay uses now. A fixed dock
 * declares every area once per dock edge; the head unit's split screen adds an area the size of DiPlay's
 * window there. The car moves CarPlay between them with updateViewArea, without reconnecting. No Android
 * types beyond preferences, so the choices are unit-tested.
 */
class CarPlayViewAreas private constructor(
    val areas: List<AirPlayViewArea>,
    private val kinds: List<Kind>,
    initial: Int,
) {
    enum class Kind { FULL_SCREEN, SPLIT_SCREEN }

    /** The area CarPlay uses now; shared by the session's host and its settings. */
    @Volatile var current: Int = initial
        private set

    fun kindOf(index: Int): Kind = kinds[index]

    /** The area of [kind] with [dockEdge] (or the edge-less one), or null if this session has none. */
    fun index(kind: Kind, dockEdge: Int?): Int? =
        areas.indices.firstOrNull { kinds[it] == kind && areas[it].dockEdge == dockEdge }

    /**
     * The area for DiPlay's window: the split-screen area in the head unit's split screen when one has
     * about the window's shape, the whole screen otherwise; the dock edge stays as it is now.
     */
    fun indexFor(width: Int, height: Int, splitScreen: Boolean): Int {
        val edge = areas[current].dockEdge
        val split = index(Kind.SPLIT_SCREEN, edge)
        if (splitScreen && split != null && closeShape(areas[split], width, height)) return split
        return index(Kind.FULL_SCREEN, edge) ?: 0
    }

    /** The area in use after moving the dock to [dockEdge], keeping the kind of area. */
    fun withDock(dockEdge: Int): Int? = index(kindOf(current), dockEdge)

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        /** Further than this (as an aspect ratio) from DiPlay's window, an area does not fit it. */
        private const val MAX_ASPECT_MISMATCH = 1.25

        /**
         * The areas for a [width] x [height] stream, or null when the whole screen as one area will do
         * (automatic dock, no split screen). [splitWindow] is the split-screen window as fractions of
         * the full window (width, height), or null without split-screen support.
         */
        fun build(width: Int, height: Int, dock: CarPlayDock, splitWindow: Pair<Float, Float>?): CarPlayViewAreas? {
            val edges = dock.edge?.let { listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM) }
                ?: listOf(null)
            if (dock.edge == null && splitWindow == null) return null
            val areas = mutableListOf<AirPlayViewArea>()
            val kinds = mutableListOf<Kind>()
            for (edge in edges) {
                areas += AirPlayViewArea(width, height, dockEdge = edge)
                kinds += Kind.FULL_SCREEN
            }
            if (splitWindow != null) {
                // The window's own size (BYD shows its bars in split screen), kept even for the encoder.
                val splitWidth = (width * splitWindow.first).toInt().coerceIn(2, width) and 1.inv()
                val splitHeight = (height * splitWindow.second).toInt().coerceIn(2, height) and 1.inv()
                for (edge in edges) {
                    areas += AirPlayViewArea(splitWidth, splitHeight, dockEdge = edge)
                    kinds += Kind.SPLIT_SCREEN
                }
            }
            val initial = areas.indices.first { kinds[it] == Kind.FULL_SCREEN && areas[it].dockEdge == dock.edge }
            return CarPlayViewAreas(areas, kinds, initial)
        }

        private fun closeShape(area: AirPlayViewArea, width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            val ratio = area.width.toDouble() / area.height / (width.toDouble() / height)
            return abs(ln(ratio)) <= ln(MAX_ASPECT_MISMATCH)
        }
    }
}

/**
 * Optional: CarPlay fills DiPlay's window in the head unit's split screen without reconnecting. The
 * window there is remembered (as fractions of the full window), so the next connection declares an area
 * of exactly that size.
 */
object SplitScreenSettings {
    private const val PREFS = "diplay_split_screen"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /**
     * The split-screen window seen last on a [portrait] or landscape screen, as fractions of the full
     * window. Before that: half the width side by side, or half the height on a portrait screen.
     */
    fun window(context: Context, portrait: Boolean): Pair<Float, Float> {
        val key = if (portrait) "portrait" else "landscape"
        val width = prefs(context).getFloat("${key}_width", 0f)
        val height = prefs(context).getFloat("${key}_height", 0f)
        return if (width in 0.1f..1f && height in 0.1f..1f) width to height else if (portrait) 1f to 0.5f else 0.5f to 1f
    }

    fun saveWindow(context: Context, portrait: Boolean, width: Float, height: Float) {
        if (width !in 0.1f..1f || height !in 0.1f..1f) return
        val key = if (portrait) "portrait" else "landscape"
        prefs(context).edit().putFloat("${key}_width", width).putFloat("${key}_height", height).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
