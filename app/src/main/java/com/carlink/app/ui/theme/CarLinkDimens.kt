package com.carlink.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.carlink.app.R
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Component sizes (DESIGN_SPEC §5.3, §5.6), one set per [UiProfile]. */
@Immutable
data class CarLinkDimens(
    val minTouch: Dp,
    val primaryButton: Dp,
    val secondaryButton: Dp,
    val buttonRadius: Dp,
    val rowOneLine: Dp,
    val rowTwoLine: Dp,
    val phoneRow: Dp,
    val railItem: Dp,
    val railWidth: Dp,
    val topBar: Dp,
    val screenMargin: Dp,
    val compactMargin: Dp,
    val gutter: Dp,
    val targetGap: Dp,
    val cardPadding: Dp,
    val iconPrimary: Dp,
    val iconSecondary: Dp,
    val iconTertiary: Dp,
    val avatarHero: Dp,
    val avatarRow: Dp,
    val statusChip: Dp,
    val focusRing: Dp,
    val sideSheetWidth: Dp,
    val contentMaxWidth: Dp,
) {
    companion object {
        val Car = CarLinkDimens(
            minTouch = 76.dp, primaryButton = 96.dp, secondaryButton = 76.dp, buttonRadius = 48.dp,
            rowOneLine = 88.dp, rowTwoLine = 112.dp, phoneRow = 112.dp, railItem = 88.dp, railWidth = 400.dp,
            topBar = 88.dp, screenMargin = 48.dp, compactMargin = 24.dp, gutter = 24.dp, targetGap = 16.dp,
            cardPadding = 40.dp, iconPrimary = 44.dp, iconSecondary = 36.dp, iconTertiary = 24.dp,
            avatarHero = 76.dp, avatarRow = 56.dp, statusChip = 48.dp, focusRing = 4.dp,
            sideSheetWidth = 560.dp, contentMaxWidth = 960.dp,
        )

        /** Material 3 phone/tablet sizes: 48dp targets, still generous for a mounted screen. */
        val Handheld = CarLinkDimens(
            minTouch = 48.dp, primaryButton = 56.dp, secondaryButton = 48.dp, buttonRadius = 28.dp,
            rowOneLine = 64.dp, rowTwoLine = 80.dp, phoneRow = 76.dp, railItem = 56.dp, railWidth = 300.dp,
            topBar = 64.dp, screenMargin = 24.dp, compactMargin = 16.dp, gutter = 16.dp, targetGap = 8.dp,
            cardPadding = 24.dp, iconPrimary = 32.dp, iconSecondary = 24.dp, iconTertiary = 18.dp,
            avatarHero = 64.dp, avatarRow = 44.dp, statusChip = 32.dp, focusRing = 3.dp,
            sideSheetWidth = 400.dp, contentMaxWidth = 840.dp,
        )
    }
}

val LocalCarLinkDimens = staticCompositionLocalOf { CarLinkDimens.Handheld }
val LocalUiProfile = staticCompositionLocalOf { UiProfile.HANDHELD }

/** Graphite and Ion shapes: large automotive radii; buttons and chips are pills. */
val CarLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp),
)

/** Inter v4 (SIL OFL 1.1, assets/licenses/Inter-OFL.txt), subset to the characters CarLink uses. */
val Inter = FontFamily(
    Font(R.font.carlink_inter_regular, FontWeight.Normal),
    Font(R.font.carlink_inter_medium, FontWeight.Medium),
    Font(R.font.carlink_inter_semibold, FontWeight.SemiBold),
)

/** Inter Display: tighter spacing drawn for large sizes (hero and display text only). */
val InterDisplay = FontFamily(Font(R.font.carlink_inter_display_semibold, FontWeight.SemiBold))

/** DESIGN_SPEC §5.7: explains state changes only; nothing longer than 350 ms, no overshoot. */
object CarMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val PRESS = 90
    const val SHORT = 150
    const val MEDIUM = 250
    const val LONG = 350
    const val THEME = 300
}

/**
 * RESEARCH_PREMIUM_UX §6.2: Inter; SemiBold with tight tracking only at display sizes (≥48sp
 * on the car canvas), Regular/Medium for anything read while driving.
 */
private fun style(size: TextUnit, line: TextUnit, medium: Boolean = false, tracking: TextUnit = 0.sp,
                  semiBold: Boolean = false, display: Boolean = false) = TextStyle(
    fontFamily = if (display) InterDisplay else Inter,
    fontSize = size,
    lineHeight = line,
    fontWeight = when {
        semiBold -> FontWeight.SemiBold
        medium -> FontWeight.Medium
        else -> FontWeight.Normal
    },
    letterSpacing = tracking,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)

internal val CarTypography = Typography(
    displayLarge = style(96.sp, 100.sp, semiBold = true, display = true, tracking = (-1.9).sp),
    displayMedium = style(64.sp, 72.sp, semiBold = true, display = true, tracking = (-1).sp),
    displaySmall = style(48.sp, 56.sp, semiBold = true, display = true, tracking = (-0.5).sp),
    headlineLarge = style(40.sp, 48.sp, semiBold = true, tracking = (-0.4).sp),
    headlineMedium = style(36.sp, 44.sp, semiBold = true, tracking = (-0.3).sp),
    headlineSmall = style(32.sp, 40.sp, medium = true),
    titleLarge = style(32.sp, 40.sp, medium = true),
    titleMedium = style(28.sp, 36.sp, medium = true),
    titleSmall = style(24.sp, 32.sp, medium = true),
    bodyLarge = style(28.sp, 40.sp),
    bodyMedium = style(24.sp, 32.sp),
    bodySmall = style(24.sp, 32.sp),
    labelLarge = style(28.sp, 32.sp, medium = true),
    labelMedium = style(24.sp, 28.sp, medium = true),
    // Eyebrow: tracked caps on glass chip headers only (≤2 words).
    labelSmall = style(20.sp, 24.sp, semiBold = true, tracking = 1.6.sp),
)

/** The same roles on the Material 3 phone scale, a step larger for a mounted, glanced-at screen. */
internal val HandheldTypography = Typography(
    displayLarge = style(56.sp, 60.sp, semiBold = true, display = true, tracking = (-1.1).sp),
    displayMedium = style(40.sp, 46.sp, semiBold = true, display = true, tracking = (-0.6).sp),
    displaySmall = style(32.sp, 38.sp, semiBold = true, display = true, tracking = (-0.3).sp),
    headlineLarge = style(28.sp, 34.sp, semiBold = true, tracking = (-0.2).sp),
    headlineMedium = style(24.sp, 30.sp, semiBold = true),
    headlineSmall = style(20.sp, 26.sp, medium = true),
    titleLarge = style(20.sp, 26.sp, medium = true),
    titleMedium = style(17.sp, 22.sp, medium = true),
    titleSmall = style(15.sp, 20.sp, medium = true),
    bodyLarge = style(17.sp, 24.sp),
    bodyMedium = style(15.sp, 22.sp),
    bodySmall = style(14.sp, 20.sp),
    labelLarge = style(16.sp, 20.sp, medium = true),
    labelMedium = style(14.sp, 18.sp, medium = true),
    labelSmall = style(12.sp, 16.sp, semiBold = true, tracking = 1.sp),
)
