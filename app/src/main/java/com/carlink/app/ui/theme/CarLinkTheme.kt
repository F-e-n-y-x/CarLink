package com.carlink.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

enum class ThemeMode { FOLLOW_SYSTEM, DARK, LIGHT }

/** Width classes (Material 3 window size classes), from the window's width in dp. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED, LARGE }

/** Height classes; a short window (split screen, ultrawide) stacks less. */
enum class HeightClass { COMPACT, MEDIUM, EXPANDED }

data class WindowLayout(val width: WidthClass, val height: HeightClass, val widthDp: Dp, val heightDp: Dp) {
    val isTwoPane: Boolean get() = width >= WidthClass.EXPANDED && heightDp >= 400.dp
    val isLandscape: Boolean get() = widthDp > heightDp
}

val LocalWindowLayout = staticCompositionLocalOf {
    WindowLayout(WidthClass.COMPACT, HeightClass.MEDIUM, 400.dp, 800.dp)
}

/** True when the system animator scale is 0 (DESIGN_SPEC §5.7 "reduce motion"). */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Root of every CarLink screen: colors (following the car or system day/night by default),
 * profile-specific typography and sizes, the window size class, and, for [UiProfile.CAR], the
 * density that maps the screen's short side to an 810dp canvas (DESIGN_SPEC §5.0).
 *
 * @param interfaceScale the driver's "Interface size" (0.9–1.3).
 */
@Composable
fun CarLinkTheme(
    profile: UiProfile,
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    interfaceScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val context = LocalContext.current
    val systemDensity = LocalDensity.current
    // The car canvas follows the physical screen, not the window, so split screen keeps sizes.
    val screenShortSidePx = remember(context) { screenShortSidePx(context) }
    val scale = interfaceScale.coerceIn(0.85f, 1.35f)
    val density = when (profile) {
        UiProfile.CAR -> Density(
            density = screenShortSidePx / CAR_CANVAS_SHORT_SIDE_DP * scale,
            fontScale = systemDensity.fontScale.coerceIn(1f, 1.3f),
        )
        UiProfile.HANDHELD -> Density(
            density = systemDensity.density * scale,
            fontScale = systemDensity.fontScale.coerceIn(0.85f, 1.3f),
        )
    }
    val reduceMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val scheme = animatedScheme(if (dark) CarLinkDark.scheme else CarLinkLight.scheme, reduceMotion)
    val dimens = if (profile == UiProfile.CAR) CarLinkDimens.Car else CarLinkDimens.Handheld

    CompositionLocalProvider(LocalDensity provides density) {
        val configuration = LocalConfiguration.current
        // Configuration dp values use the system density; convert through pixels to CarLink's.
        val widthDp = with(density) { (configuration.screenWidthDp * systemDensity.density).toDp() }
        val heightDp = with(density) { (configuration.screenHeightDp * systemDensity.density).toDp() }
        val layout = WindowLayout(widthClass(widthDp), heightClass(heightDp), widthDp, heightDp)
        CompositionLocalProvider(
            LocalUiProfile provides profile,
            LocalCarLinkDimens provides dimens,
            LocalCarLinkColors provides if (dark) CarLinkDark.ext else CarLinkLight.ext,
            LocalWindowLayout provides layout,
            LocalReduceMotion provides reduceMotion,
            LocalMinimumInteractiveComponentSize provides dimens.minTouch,
            // Overscroll stretch reads as lag on head-unit panels (DESIGN_SPEC §8).
            LocalOverscrollFactory provides if (profile == UiProfile.CAR) null else LocalOverscrollFactory.current,
        ) {
            MaterialTheme(
                colorScheme = scheme,
                typography = if (profile == UiProfile.CAR) CarTypography else HandheldTypography,
                shapes = CarLinkShapes,
                content = content,
            )
        }
    }
}

private const val CAR_CANVAS_SHORT_SIDE_DP = 810f

@Suppress("DEPRECATION") // getRealMetrics is the API-25-compatible way to read the panel size.
private fun screenShortSidePx(context: android.content.Context): Float {
    val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
    val display = manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    val metrics = android.util.DisplayMetrics()
    if (display != null) display.getRealMetrics(metrics) else metrics.setTo(context.resources.displayMetrics)
    return min(metrics.widthPixels, metrics.heightPixels).toFloat()
}

internal fun widthClass(width: Dp) = when {
    width < 600.dp -> WidthClass.COMPACT
    width < 840.dp -> WidthClass.MEDIUM
    width < 1200.dp -> WidthClass.EXPANDED
    else -> WidthClass.LARGE
}

internal fun heightClass(height: Dp) = when {
    height < 480.dp -> HeightClass.COMPACT
    height < 900.dp -> HeightClass.MEDIUM
    else -> HeightClass.EXPANDED
}

/** Day/night is a 300 ms cross-fade of the same layout (DESIGN_SPEC §5.1). */
@Composable
private fun animatedScheme(target: ColorScheme, reduceMotion: Boolean): ColorScheme {
    val spec = tween<androidx.compose.ui.graphics.Color>(if (reduceMotion) 0 else CarMotion.THEME)
    @Composable fun c(color: androidx.compose.ui.graphics.Color) = animateColorAsState(color, spec, label = "theme").value
    return target.copy(
        primary = c(target.primary), onPrimary = c(target.onPrimary),
        primaryContainer = c(target.primaryContainer), onPrimaryContainer = c(target.onPrimaryContainer),
        background = c(target.background), onBackground = c(target.onBackground),
        surface = c(target.surface), onSurface = c(target.onSurface),
        onSurfaceVariant = c(target.onSurfaceVariant),
        surfaceContainerLowest = c(target.surfaceContainerLowest), surfaceContainerLow = c(target.surfaceContainerLow),
        surfaceContainer = c(target.surfaceContainer), surfaceContainerHigh = c(target.surfaceContainerHigh),
        surfaceContainerHighest = c(target.surfaceContainerHighest),
        outline = c(target.outline), outlineVariant = c(target.outlineVariant),
    )
}

/** Shorthands for the custom tokens. */
object CarLinkTokens {
    val colors: CarLinkExtColors @Composable get() = LocalCarLinkColors.current
    val dimens: CarLinkDimens @Composable get() = LocalCarLinkDimens.current
    val layout: WindowLayout @Composable get() = LocalWindowLayout.current
    val profile: UiProfile @Composable get() = LocalUiProfile.current
}
