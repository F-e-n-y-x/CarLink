package com.carlink.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color tokens from design/DESIGN_SPEC.md §5.1. Every text/icon pair is ≥4.5:1 and every
 * outline ≥3:1 on the surfaces it is used on. One accent (Link Cyan), reserved for interactive
 * elements and the active connection stage; the engine tints appear only on engine badges.
 */
@Immutable
data class CarLinkExtColors(
    val onSurfaceTertiary: Color,
    val success: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val engineCarPlay: Color,
    val engineAndroidAuto: Color,
)

internal object CarLinkDark {
    val scheme: ColorScheme = darkColorScheme(
        primary = Color(0xFF5CCFE6), onPrimary = Color(0xFF00222A),
        primaryContainer = Color(0xFF0E3A45), onPrimaryContainer = Color(0xFFB8EEFA),
        secondary = Color(0xFFAEB8C5), onSecondary = Color(0xFF0B0F14),
        secondaryContainer = Color(0xFF232B36), onSecondaryContainer = Color(0xFFE8EDF3),
        tertiary = Color(0xFF5CCFE6), onTertiary = Color(0xFF00222A),
        background = Color(0xFF0B0F14), onBackground = Color(0xFFE8EDF3),
        surface = Color(0xFF0B0F14), onSurface = Color(0xFFE8EDF3),
        surfaceVariant = Color(0xFF1C232D), onSurfaceVariant = Color(0xFFAEB8C5),
        surfaceContainerLowest = Color(0xFF080B0F), surfaceContainerLow = Color(0xFF11161D),
        surfaceContainer = Color(0xFF161C24), surfaceContainerHigh = Color(0xFF1C232D),
        surfaceContainerHighest = Color(0xFF232B36),
        surfaceBright = Color(0xFF232B36), surfaceDim = Color(0xFF0B0F14),
        inverseSurface = Color(0xFFE8EDF3), inverseOnSurface = Color(0xFF11161D), inversePrimary = Color(0xFF006273),
        outline = Color(0xFF6B7788), outlineVariant = Color(0xFF2C3540),
        error = Color(0xFFFF8F87), onError = Color(0xFF3B0906),
        errorContainer = Color(0xFF4A1512), onErrorContainer = Color(0xFFFFDAD6),
        scrim = Color(0xFF000000),
    )
    val ext = CarLinkExtColors(
        onSurfaceTertiary = Color(0xFF8A95A3), success = Color(0xFF6EDC8C),
        warning = Color(0xFFFFC46B), warningContainer = Color(0xFF3D2A00), onWarningContainer = Color(0xFFFFE2B3),
        engineCarPlay = Color(0xFF6EDC8C), engineAndroidAuto = Color(0xFF8AB4F8),
    )
}

internal object CarLinkLight {
    val scheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF006273), onPrimary = Color.White,
        primaryContainer = Color(0xFFC9EEF6), onPrimaryContainer = Color(0xFF002A33),
        secondary = Color(0xFF45505D), onSecondary = Color.White,
        secondaryContainer = Color(0xFFDAE0E7), onSecondaryContainer = Color(0xFF11161D),
        tertiary = Color(0xFF006273), onTertiary = Color.White,
        background = Color(0xFFF2F4F7), onBackground = Color(0xFF11161D),
        surface = Color(0xFFF2F4F7), onSurface = Color(0xFF11161D),
        surfaceVariant = Color(0xFFE3E7EC), onSurfaceVariant = Color(0xFF45505D),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
        surfaceContainer = Color(0xFFEBEEF2), surfaceContainerHigh = Color(0xFFE3E7EC),
        surfaceContainerHighest = Color(0xFFDAE0E7),
        surfaceBright = Color.White, surfaceDim = Color(0xFFDAE0E7),
        inverseSurface = Color(0xFF161C24), inverseOnSurface = Color(0xFFE8EDF3), inversePrimary = Color(0xFF5CCFE6),
        outline = Color(0xFF737E8C), outlineVariant = Color(0xFFC4CBD4),
        error = Color(0xFFB3261E), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        scrim = Color(0xFF000000),
    )
    val ext = CarLinkExtColors(
        onSurfaceTertiary = Color(0xFF55606D), success = Color(0xFF176B34),
        warning = Color(0xFF8A5000), warningContainer = Color(0xFFFFE2B3), onWarningContainer = Color(0xFF2B1700),
        engineCarPlay = Color(0xFF176B34), engineAndroidAuto = Color(0xFF1D58B8),
    )
}

val LocalCarLinkColors = staticCompositionLocalOf { CarLinkDark.ext }
