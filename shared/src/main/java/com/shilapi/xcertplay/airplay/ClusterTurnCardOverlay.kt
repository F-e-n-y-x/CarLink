package com.shilapi.xcertplay.airplay

/**
 * Where DiPlay draws the instruction card on top of the dashboard map.
 *
 * Placement is a percent of the full 1920×720 panel so Left/Right can reach the
 * cluster edges. Size still follows the measured centre navi window so Large
 * does not cover half the cluster.
 */
object ClusterTurnCardOverlay {
    data class CardRect(val left: Int, val top: Int, val width: Int, val height: Int)

    const val STEP_PERCENT = 5
    const val DEFAULT_X_PERCENT = 75
    const val DEFAULT_Y_PERCENT = 30
    val xPercents = listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90)
    val yPercents = listOf(15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70)

    fun card(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        size: CarPlayClusterDisplay.OverlaySize,
    ): CardRect {
        require(panelWidth > 0 && panelHeight > 0)
        val window = visibleWindow(panelWidth, panelHeight)
        val widthFraction = when (size) {
            CarPlayClusterDisplay.OverlaySize.SMALL -> 0.42f
            CarPlayClusterDisplay.OverlaySize.MEDIUM -> 0.56f
            CarPlayClusterDisplay.OverlaySize.LARGE -> 0.70f
        }
        val heightFraction = when (size) {
            CarPlayClusterDisplay.OverlaySize.SMALL -> 0.22f
            CarPlayClusterDisplay.OverlaySize.MEDIUM -> 0.28f
            CarPlayClusterDisplay.OverlaySize.LARGE -> 0.34f
        }
        val width = (window.width * widthFraction).toInt().coerceAtLeast(140).coerceAtMost(panelWidth)
        val height = (window.height * heightFraction).toInt().coerceAtLeast(72).coerceAtMost(panelHeight)
        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)
        val left = (panelWidth * x / 100 - width / 2).coerceIn(0, panelWidth - width)
        val top = (panelHeight * y / 100 - height / 2).coerceIn(0, panelHeight - height)
        return CardRect(left, top, width, height)
    }

    fun snap(value: Int, choices: List<Int>): Int =
        choices.minByOrNull { kotlin.math.abs(it - value) } ?: choices.first()

    internal fun visibleWindow(panelWidth: Int, panelHeight: Int): CardRect {
        val area = CarPlayClusterDisplay.SAFE_AREA_PERCENT
        val left = panelWidth * area.left / 100
        val top = panelHeight * area.top / 100
        val width = panelWidth * (100 - area.left - area.right) / 100
        val height = panelHeight * (100 - area.top - area.bottom) / 100
        return CardRect(left, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}
