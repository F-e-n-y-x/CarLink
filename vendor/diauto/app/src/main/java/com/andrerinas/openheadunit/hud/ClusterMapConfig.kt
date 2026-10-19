package com.andrerinas.openheadunit.hud

/**
 * What DiAuto declares to the phone for the BYD instrument-cluster ("dashboard") screen, and how
 * the decoded picture is cropped back onto it. Pure, so the arithmetic is unit-tested.
 *
 * Android Auto only offers fixed codec sizes, so the 8:3 dashboard is a codec frame with equal
 * top/bottom margins: the phone draws only inside the margins, and [cropMatrix] shows just that
 * part. Content insets are the phone's safe area: navigation apps keep the car marker inside it,
 * which is how the marker is moved without touching the map itself.
 */
internal object ClusterMapConfig {

    /** How much of the map fits: a smaller stream is enlarged onto the panel. */
    enum class Size(val value: Int, val codecResolution: Int, val codecWidth: Int, val codecHeight: Int) {
        /** 1920x720 drawn at native size. */
        NORMAL(0, RES_1920x1080, 1920, 1080),
        /** 1280x480 enlarged 1.5x: a closer, larger map. */
        LARGE(1, RES_1280x720, 1280, 720);

        companion object { fun of(value: Int) = entries.firstOrNull { it.value == value } ?: NORMAL }
    }

    /** Size of map labels, icons and the car marker, as the display density the phone draws for. */
    enum class Detail(val value: Int, val dpi: Int) {
        SMALL(0, 120), NORMAL(1, 160), LARGE(2, 213), EXTRA_LARGE(3, 240);

        companion object { fun of(value: Int) = entries.firstOrNull { it.value == value } ?: NORMAL }
    }

    /**
     * How wide the safe area is. Android Auto keeps the car marker in its middle and lays the
     * turn and trip cards out inside it: in a narrow box they stack above and below the car,
     * covering the road ahead, while on a wide screen it puts them in the left corners, as on
     * the main screen. The wider choices hand the dashboard map a wide box for that layout.
     */
    enum class Width(val value: Int, val left: Int, val right: Int) {
        /** BYD's small navi window, as before. */
        COMPACT(0, BASE_LEFT, BASE_RIGHT),
        /** About two thirds of the panel, LIVI's default cluster safe area. */
        WIDE(1, 16, 16),
        /** Nearly the whole panel. */
        FULL(2, 3, 3);

        companion object { fun of(value: Int) = entries.firstOrNull { it.value == value } ?: COMPACT }
    }

    /** The cluster theme picked on the car. On DiLink 5.1 it decides which layer shows the map, and where. */
    enum class Theme(val value: Int) {
        SCENARIO(0), MAP(1), SIMPLE(2);

        companion object { fun of(value: Int) = entries.firstOrNull { it.value == value } ?: SCENARIO }
    }

    /** The part of the 1920x720 panel where the car shows the map, in panel pixels. */
    data class Window(val left: Int, val top: Int, val width: Int, val height: Int, val fullMap: Boolean)

    /**
     * DiLink 5.1 firmware whose three-layer cluster DiPlay measured: a Han L, and a Leopard 5 Ultra
     * 2025 (DiPlay #301). Their cluster shows the shared `_0` layer in the Map theme and `_1` in
     * Scenario and Simple, never the base layer DiLink 5.0 uses.
     */
    private val MEASURED_DILINK51 = setOf(
        "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys",
        "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.021352:user/release-keys",
    )

    fun measured(fingerprint: String): Boolean = fingerprint in MEASURED_DILINK51

    /**
     * Where [theme] shows the map on a measured DiLink 5.1 cluster: the Map theme's full-width
     * window between the speed readout and the gear/range bar, or the side window on the right of
     * Scenario and Simple.
     */
    fun window(theme: Theme): Window =
        if (theme == Theme.MAP) Window(0, 144, PANEL_WIDTH, 480, true)
        else Window(1320, 0, 600, PANEL_HEIGHT, false)

    /**
     * Whether DiAuto's map should show, given the cluster's [theme] and whether its [mapCardVisible]
     * (the Map theme, or the side map card), for a map drawn on the full-map layer
     * ([fullMapLayer] true) or the side layer (false). Scenario puts both layers over its whole
     * screen, so a map on the layer of another theme must hide. Unknown state keeps it showing.
     */
    fun shownOnLayer(theme: Theme?, mapCardVisible: Boolean, fullMapLayer: Boolean?): Boolean = when {
        theme == null || fullMapLayer == null -> true
        fullMapLayer -> theme == Theme.MAP
        else -> theme != Theme.MAP && mapCardVisible
    }

    /** The theme the cluster last reported while the dashboard map runs; null when unknown. */
    @Volatile var reportedTheme: Theme? = null

    /** Simple keeps a dark background on that firmware; Scenario and Map are light. */
    fun dark(theme: Theme): Boolean = theme == Theme.SIMPLE

    // VideoCodecResolutionType values.
    const val RES_1280x720 = 2
    const val RES_1920x1080 = 3

    const val DISPLAY_TYPE_CLUSTER = 1

    /** The cluster is the second display; the main screen is display 0. */
    const val DISPLAY_ID = 1

    /** The dashboard panel these presets are made for (8:3). */
    const val PANEL_WIDTH = 1920
    const val PANEL_HEIGHT = 720

    /**
     * BYD's "Small screen navi" window is about x 31–68 %, y 16–89 % of the panel (measured by
     * DiPlay on a DiLink 5 cluster); this safe area keeps the marker inside it in both modes.
     */
    const val BASE_LEFT = 35
    const val BASE_RIGHT = 36
    const val BASE_TOP = 16
    const val BASE_BOTTOM = 25

    /** Marker steps move the safe area by this many percent of the panel. */
    const val STEP_PERCENT = 10
    val HORIZONTAL_STEPS = -3..3 // negative = left
    val VERTICAL_STEPS = -2..2 // negative = up

    /** Never let the safe area shrink below this share of the panel in either direction. */
    private const val MIN_SPAN_PERCENT = 10

    data class Insets(val top: Int, val bottom: Int, val left: Int, val right: Int)

    data class Profile(
        val codecResolution: Int,
        val codecWidth: Int,
        val codecHeight: Int,
        val widthMargin: Int,
        val heightMargin: Int,
        val contentWidth: Int,
        val contentHeight: Int,
        val density: Int,
        val contentInsets: Insets,
    ) {
        /** Half of each margin, the UiConfig margins the phone leaves blank on each side. */
        val margins: Insets get() = Insets(heightMargin / 2, heightMargin - heightMargin / 2,
            widthMargin / 2, widthMargin - widthMargin / 2)
    }

    fun profile(
        size: Size, detail: Detail, markerX: Int, markerY: Int,
        width: Width = Width.COMPACT, window: Window? = null,
    ): Profile {
        // Keep the panel's 8:3 shape inside the codec frame; the rest is margin.
        val contentWidth = size.codecWidth
        val contentHeight = contentWidth * PANEL_HEIGHT / PANEL_WIDTH
        val heightMargin = size.codecHeight - contentHeight
        return Profile(
            codecResolution = size.codecResolution,
            codecWidth = size.codecWidth,
            codecHeight = size.codecHeight,
            widthMargin = 0,
            heightMargin = heightMargin,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            // Apps size labels, icons and the marker in dp, so a higher density draws them larger
            // and shows less area around the car. Left unscaled on the LARGE stream on purpose:
            // its 1.5x enlargement then makes everything 1.5x larger, which is what LARGE is for.
            density = detail.dpi,
            contentInsets = insets(contentWidth, contentHeight, markerX, markerY, width, window),
        )
    }

    /**
     * The safe area in content pixels for marker steps [markerX] and [markerY]. With a measured
     * [window] it starts as that window, so the car marker sits in the middle of what the cluster
     * shows; the full-width Map window keeps clear of the trip readouts on both sides.
     */
    fun insets(
        contentWidth: Int, contentHeight: Int, markerX: Int, markerY: Int, width: Width = Width.COMPACT,
        window: Window? = null,
    ): Insets {
        val dx = markerX.coerceIn(HORIZONTAL_STEPS) * STEP_PERCENT
        val dy = markerY.coerceIn(VERTICAL_STEPS) * STEP_PERCENT
        val base = basePercent(width, window)
        val (left, right) = shift(base.left, base.right, dx)
        val (top, bottom) = shift(base.top, base.bottom, dy)
        return Insets(
            top = contentHeight * top / 100,
            bottom = contentHeight * bottom / 100,
            left = contentWidth * left / 100,
            right = contentWidth * right / 100,
        )
    }

    /** The unshifted safe area, in percent of the panel. */
    private fun basePercent(width: Width, window: Window?): Insets =
        if (window == null) Insets(BASE_TOP, BASE_BOTTOM, width.left, width.right) else UNIFIED

    /**
     * Measured DiLink 5.1: one safe area for every cluster theme, told to the phone once per
     * connection: as wide as the side window and centred on the panel. Changing it mid-session
     * (UpdateUiConfigRequest on the dashboard channel) made the phone drop the connection, so a
     * theme change instead slides the same picture into the window the cluster shows
     * ([mapOffset]), as DiPlay crops one stream into either window.
     */
    private val UNIFIED = Insets(top = 17, bottom = 17, left = 35, right = 35)

    /**
     * Where the panel-size map goes for [window], in panel pixels: the safe area's centre, where
     * the phone keeps the car, lands on the window's centre.
     */
    fun mapOffset(window: Window): Pair<Int, Int> =
        (window.left + window.width / 2 - PANEL_WIDTH / 2) to (window.top + window.height / 2 - PANEL_HEIGHT / 2)

    /** Moves a (start, end) inset pair by [delta] percent, keeping both edges on the panel. */
    private fun shift(start: Int, end: Int, delta: Int): Pair<Int, Int> {
        val span = 100 - start - end
        val newStart = (start + delta).coerceIn(0, 100 - maxOf(span, MIN_SPAN_PERCENT))
        return newStart to (100 - span - newStart).coerceAtLeast(0)
    }

    /**
     * Scale and translation for a TextureView of [viewWidth]x[viewHeight] whose buffer is the
     * whole codec frame, so that only the drawn content (inside the margins) fills the view.
     * Returned as (scaleX, scaleY, translateX, translateY) for Matrix.setScale + postTranslate.
     */
    fun cropMatrix(profile: Profile, viewWidth: Int, viewHeight: Int): FloatArray {
        val contentLeft = profile.margins.left
        val contentTop = profile.margins.top
        val scaleX = profile.codecWidth.toFloat() / profile.contentWidth
        val scaleY = profile.codecHeight.toFloat() / profile.contentHeight
        val translateX = -contentLeft.toFloat() * viewWidth / profile.contentWidth
        val translateY = -contentTop.toFloat() * viewHeight / profile.contentHeight
        return floatArrayOf(scaleX, scaleY, translateX, translateY)
    }
}
