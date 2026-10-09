package com.carlink.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.carlink.app.ui.theme.LocalUiProfile
import com.carlink.app.ui.theme.LocalWindowLayout
import com.carlink.app.ui.theme.UiProfile
import com.carlink.app.ui.theme.WindowLayout
import com.carlink.app.ui.theme.heightClass
import com.carlink.app.ui.theme.widthClass
import kotlin.math.max
import kotlin.math.min

/**
 * Large-handheld scale (design/RESEARCH_HOME_V2.md §4): a tablet mounted in a dash is read from
 * arm's length, like a head unit, so on the HANDHELD profile Home's stage grows by
 * `min(longSide / 1100dp, shortSide / 690dp)`, clamped to 1–1.35×. A Redmi Pad Pro in landscape
 * (≈1460×914dp) gets ≈1.32× and reads like the 1440×810dp car canvas; an 800×1280dp tablet gets
 * 1.16×; phones stay at 1×. The car profile already sizes from the panel and is left alone.
 */
internal fun homeScale(profile: UiProfile, layout: WindowLayout): Float {
    if (profile != UiProfile.HANDHELD) return 1f
    val long = max(layout.widthDp.value, layout.heightDp.value)
    val short = min(layout.widthDp.value, layout.heightDp.value)
    return min(long / 1100f, short / 690f).coerceIn(1f, 1.35f)
}

/** The density and window layout Home had before [HomeScaled] enlarged them. */
private class BaseMetrics(val density: Density, val layout: WindowLayout)

private val LocalBaseMetrics = staticCompositionLocalOf<BaseMetrics?> { null }

/** Renders [content] at Home's large-handheld scale, with the window layout re-measured in the new dp. */
@Composable
internal fun HomeScaled(content: @Composable () -> Unit) {
    val layout = LocalWindowLayout.current
    val scale = homeScale(LocalUiProfile.current, layout)
    if (scale <= 1.001f) return content()
    val density = LocalDensity.current
    val width = layout.widthDp / scale
    val height = layout.heightDp / scale
    CompositionLocalProvider(
        LocalBaseMetrics provides BaseMetrics(density, layout),
        LocalDensity provides Density(density.density * scale, density.fontScale),
        LocalWindowLayout provides WindowLayout(widthClass(width), heightClass(height), width, height),
        content = content,
    )
}

/** Sheets and dialogs opened from the stage are separate windows: they keep the device's own sizes. */
@Composable
internal fun HomeUnscaled(content: @Composable () -> Unit) {
    val base = LocalBaseMetrics.current ?: return content()
    CompositionLocalProvider(
        LocalBaseMetrics provides null,
        LocalDensity provides base.density,
        LocalWindowLayout provides base.layout,
        content = content,
    )
}
