package com.carlink.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * "Graphite and Ion" (design/RESEARCH_PREMIUM_UX.md §6.1): a near-black graphite canvas lit by
 * one soft light source, glass cards, and a single cyan "Ion" accent reserved for things the
 * driver can tap. The day variant is a warm graphite, not a white Material sheet. All text
 * pairs are ≥4.5:1 on the surfaces they are used on.
 */
@Immutable
data class CarLinkExtColors(
    val onSurfaceTertiary: Color,
    /** "Live": connected, success checks; always with an icon. */
    val success: Color,
    /** "Caution": applies next connection, Lab frame. */
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val engineCarPlay: Color,
    val engineAndroidAuto: Color,
    /** Letterbox and projection hand-off. */
    val bgDeep: Color,
    /** The single soft light source of the background atmosphere. */
    val atmosphereLight: Color,
    /** A second, fainter light that gives the canvas depth. */
    val atmosphereFill: Color,
    val glassFill: Color,
    val glassFillPressed: Color,
    val glassStrokeTop: Color,
    val glassStrokeBottom: Color,
)

internal object CarLinkDark {
    val scheme: ColorScheme = darkColorScheme(
        primary = Color(0xFF5BD6F0), onPrimary = Color(0xFF03222B),
        primaryContainer = Color(0xFF12343E), onPrimaryContainer = Color(0xFFBFF2FB),
        secondary = Color(0xFFA9B3C0), onSecondary = Color(0xFF0C1016),
        secondaryContainer = Color(0xFF24282D), onSecondaryContainer = Color(0xFFEEF2F6),
        tertiary = Color(0xFF7EE2A8), onTertiary = Color(0xFF05301A),
        background = Color(0xFF0C1016), onBackground = Color(0xFFEEF2F6),
        surface = Color(0xFF0C1016), onSurface = Color(0xFFEEF2F6),
        surfaceVariant = Color(0xFF24282D), onSurfaceVariant = Color(0xFFA9B3C0),
        surfaceContainerLowest = Color(0xFF07090D), surfaceContainerLow = Color(0xFF12161C),
        surfaceContainer = Color(0xFF1B1E24), surfaceContainerHigh = Color(0xFF24282D),
        surfaceContainerHighest = Color(0xFF2C3036),
        surfaceBright = Color(0xFF2C3036), surfaceDim = Color(0xFF0C1016),
        inverseSurface = Color(0xFFEEF2F6), inverseOnSurface = Color(0xFF0D1117), inversePrimary = Color(0xFF006A7E),
        outline = Color(0xFF6E7884), outlineVariant = Color(0xFF2A2F37),
        error = Color(0xFFFF7A70), onError = Color(0xFF3A0905),
        errorContainer = Color(0xFF3D1715), onErrorContainer = Color(0xFFFFD9D5),
        scrim = Color(0xFF000000),
    )
    val ext = CarLinkExtColors(
        onSurfaceTertiary = Color(0xFF8A95A3), success = Color(0xFF7EE2A8),
        warning = Color(0xFFFFB547), warningContainer = Color(0xFF3A2A0E), onWarningContainer = Color(0xFFFFE3B8),
        engineCarPlay = Color(0xFFEEF2F6), engineAndroidAuto = Color(0xFF9DB8FF),
        bgDeep = Color(0xFF07090D),
        atmosphereLight = Color(0xFF16324A), atmosphereFill = Color(0xFF111A26),
        glassFill = Color(0x0FFFFFFF), glassFillPressed = Color(0x1AFFFFFF),
        glassStrokeTop = Color(0x29FFFFFF), glassStrokeBottom = Color(0x0AFFFFFF),
    )
}

internal object CarLinkLight {
    val scheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF006A7E), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCBEFF6), onPrimaryContainer = Color(0xFF00303A),
        secondary = Color(0xFF46505C), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDDE2E8), onSecondaryContainer = Color(0xFF0D1117),
        tertiary = Color(0xFF18703F), onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFE8EBEF), onBackground = Color(0xFF0D1117),
        surface = Color(0xFFE8EBEF), onSurface = Color(0xFF0D1117),
        surfaceVariant = Color(0xFFE4E8EC), onSurfaceVariant = Color(0xFF46505C),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F8FA),
        surfaceContainer = Color(0xFFF1F3F5), surfaceContainerHigh = Color(0xFFE4E8EC),
        surfaceContainerHighest = Color(0xFFDADFE5),
        surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFDADFE5),
        inverseSurface = Color(0xFF1B1E24), inverseOnSurface = Color(0xFFEEF2F6), inversePrimary = Color(0xFF5BD6F0),
        outline = Color(0xFF77818C), outlineVariant = Color(0xFFC9CFD6),
        error = Color(0xFFC2352B), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFCDAD6), onErrorContainer = Color(0xFF410002),
        scrim = Color(0xFF000000),
    )
    val ext = CarLinkExtColors(
        onSurfaceTertiary = Color(0xFF5A6470), success = Color(0xFF18703F),
        warning = Color(0xFF965A00), warningContainer = Color(0xFFFFE3B8), onWarningContainer = Color(0xFF2B1700),
        engineCarPlay = Color(0xFF0D1117), engineAndroidAuto = Color(0xFF2F5BD0),
        bgDeep = Color(0xFF07090D),
        atmosphereLight = Color(0xFFF3EEE6), atmosphereFill = Color(0xFFDDE2E8),
        glassFill = Color(0x9EFFFFFF), glassFillPressed = Color(0xC7FFFFFF),
        glassStrokeTop = Color(0xCCFFFFFF), glassStrokeBottom = Color(0x33FFFFFF),
    )
}

val LocalCarLinkColors = staticCompositionLocalOf { CarLinkDark.ext }
