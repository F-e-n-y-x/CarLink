package com.carlink.app.switcher

import com.carlink.core.connection.SessionRequest
import com.carlink.core.connection.SessionState
import com.carlink.core.core.Engine
import kotlin.math.roundToInt

/**
 * When the floating switcher may be on screen. Pure, so every combination is unit-tested.
 *
 * The bubble belongs to a running projection: it shows only while a session is CONNECTED and that
 * session's own projection (CarPlayHostActivity for CarPlay, AapProjectionActivity for Android
 * Auto) is in front, never over CarLink's own screens (Home already offers every action), and only
 * when the driver left the setting on and Android lets CarLink draw over other apps.
 */
object SwitcherRules {
    fun shouldShow(
        session: SessionState,
        /** Engines whose projection activity is resumed right now. */
        projectionsInFront: Set<Engine>,
        /** A CarLink screen (Home, Settings, …) is resumed; it wins over the projection. */
        carLinkScreenInFront: Boolean,
        canDrawOverlays: Boolean,
        enabled: Boolean,
        /** The "Switch to …?" prompt is up; it is modal, so the bubble steps aside. */
        promptShowing: Boolean = false,
    ): Boolean {
        if (!enabled || !canDrawOverlays || carLinkScreenInFront || promptShowing) return false
        val active = session as? SessionState.Active ?: return false
        if (active.phase != SessionState.Phase.Connected) return false
        return active.request.engine in projectionsInFront
    }

    /**
     * The "Switch to <phone>?" prompt (another phone plugged in during a session) shows whenever
     * the session offers a switch and CarLink may draw over other apps: over the projection, over
     * CarLink's own screens, and independent of the floating-switcher setting (it is a question,
     * not a convenience). Without the permission, Home asks instead.
     */
    fun shouldShowSwitchPrompt(pendingSwitch: SessionRequest?, canDrawOverlays: Boolean): Boolean =
        pendingSwitch != null && canDrawOverlays

    /** The prompt declines by itself after this long, so an unattended car keeps its session. */
    const val SWITCH_PROMPT_TIMEOUT_MS = 30_000L
}

/** Which screen edge the bubble rests on. */
enum class Edge { LEFT, RIGHT }

/** The parts of the screen covered by system bars and cut-outs, in pixels. */
data class SafeInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** Where the bubble's window sits: its top-left corner in screen pixels, and the edge it rests on. */
data class BubblePlacement(val edge: Edge, val x: Int, val y: Int)

/**
 * The bubble's geometry. All values are pixels; the window is [bubble] × [bubble] with its
 * top-left corner at (x, y) on a [screenWidth] × [screenHeight] screen.
 *
 * - On release it snaps to the nearer left or right edge, [margin] inside the system bars, so it
 *   never rests over the middle of the projection (where maps and media controls are).
 * - Its height is stored as a fraction of the free range, so the same spot is kept when the
 *   screen size or the bars change (per orientation; see [SwitcherController]).
 */
data class BubbleGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val bubble: Int,
    val margin: Int,
    val insets: SafeInsets = SafeInsets(),
) {
    private val minX get() = insets.left + margin
    private val maxX get() = (screenWidth - insets.right - margin - bubble).coerceAtLeast(minX)
    private val minY get() = insets.top + margin
    private val maxY get() = (screenHeight - insets.bottom - margin - bubble).coerceAtLeast(minY)

    /** Snaps a bubble dropped with its top-left corner at ([x], [y]). */
    fun snap(x: Int, y: Int): BubblePlacement {
        val centre = x + bubble / 2f
        val edge = if (centre < screenWidth / 2f) Edge.LEFT else Edge.RIGHT
        return place(edge, y)
    }

    /** The resting position on [edge] at height [y] (clamped clear of the bars). */
    fun place(edge: Edge, y: Int): BubblePlacement =
        BubblePlacement(edge, if (edge == Edge.LEFT) minX else maxX, y.coerceIn(minY, maxY))

    /** Keeps a bubble being dragged on screen (it may cross the middle while the finger moves it). */
    fun clampDrag(x: Int, y: Int): Pair<Int, Int> =
        x.coerceIn(0, (screenWidth - bubble).coerceAtLeast(0)) to y.coerceIn(0, (screenHeight - bubble).coerceAtLeast(0))

    /** 0 at the highest resting spot, 1 at the lowest. */
    fun fractionOf(placement: BubblePlacement): Float =
        if (maxY == minY) 0f else ((placement.y - minY).toFloat() / (maxY - minY)).coerceIn(0f, 1f)

    fun fromFraction(edge: Edge, fraction: Float): BubblePlacement =
        place(edge, minY + ((maxY - minY) * fraction.coerceIn(0f, 1f)).roundToInt())

    /** Where a bubble goes the first time: the right edge, a third of the way down. */
    fun default(): BubblePlacement = fromFraction(Edge.RIGHT, DEFAULT_FRACTION)

    companion object {
        const val DEFAULT_FRACTION = 0.33f
    }
}
