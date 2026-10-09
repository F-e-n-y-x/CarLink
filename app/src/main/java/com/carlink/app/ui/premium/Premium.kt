package com.carlink.app.ui.premium

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.LocalLiteRendering
import com.carlink.app.ui.theme.LocalReduceMotion
import java.util.Locale
import kotlin.random.Random

/**
 * Graphite and Ion materials (design/RESEARCH_PREMIUM_UX.md §5–6). Three materials only:
 * Atmosphere (background), Glass (cards, chips) and solid Ion (the primary button). No runtime
 * blur and no shadows: head-unit GPUs are mid-range, so depth comes from light and tone.
 */

/**
 * Full-bleed canvas: one soft key light (top-start by default; Home puts it behind its dial), an
 * optional state glow, an optional floor vignette that grounds a bar docked at the bottom, and
 * noise against banding. In lite rendering it is the background colour alone: the four full-screen
 * gradients and the grain are the most expensive thing CarLink draws on a weak GPU.
 */
@Composable
fun Atmosphere(modifier: Modifier = Modifier, glow: Color = Color.Transparent, glowCenter: Offset = Offset(0.72f, 0.55f),
               lightCenter: Offset = Offset(0.08f, -0.1f), glowRadius: Float? = null, floor: Boolean = false,
               content: @Composable BoxScope.() -> Unit) {
    val colors = CarLinkTokens.colors
    val base = MaterialTheme.colorScheme.background
    if (LocalLiteRendering.current) {
        Box(modifier.background(base), content = content)
        return
    }
    val animatedGlow by animateColorAsState(glow, tween(if (LocalReduceMotion.current) 0 else 600), label = "glow")
    val noise = rememberNoise()
    val floorColor = if (base.luminance() < 0.5f) Color.Black.copy(alpha = 0.45f) else colors.atmosphereFill
    Box(
        modifier
            .background(base)
            .atmosphere(colors.atmosphereLight, colors.atmosphereFill, animatedGlow, glowCenter, glowRadius, lightCenter,
                if (floor) floorColor else Color.Transparent, noise),
        content = content,
    )
}

private fun Modifier.atmosphere(light: Color, fill: Color, glow: Color, glowCenter: Offset, glowRadius: Float?,
                                lightCenter: Offset, floor: Color, noise: ImageBitmap) =
    drawBehind {
        val diagonal = kotlin.math.hypot(size.width, size.height)
        // The single key light, like a cabin light on graphite.
        drawRect(Brush.radialGradient(listOf(light, light.copy(alpha = 0f)),
            center = Offset(size.width * lightCenter.x, size.height * lightCenter.y), radius = diagonal * 0.75f))
        // A broad, faint fill from the bottom-end for depth.
        drawRect(Brush.radialGradient(listOf(fill.copy(alpha = 0.7f), fill.copy(alpha = 0f)),
            center = Offset(size.width, size.height * 1.1f), radius = diagonal * 0.6f))
        if (glow.alpha > 0f) {
            // The state's light behind the focal object; three stops so it falls off softly, not as a disc.
            val strength = glow.alpha * 0.45f
            drawRect(Brush.radialGradient(
                0f to glow.copy(alpha = strength), 0.45f to glow.copy(alpha = strength * 0.4f), 1f to glow.copy(alpha = 0f),
                center = Offset(size.width * glowCenter.x, size.height * glowCenter.y),
                radius = glowRadius ?: (size.minDimension * 0.62f)))
        }
        if (floor.alpha > 0f) {
            // The floor: a ground under the docked status bar, so the stage does not float.
            drawRect(Brush.verticalGradient(listOf(floor.copy(alpha = 0f), floor), startY = size.height * 0.5f, endY = size.height))
        }
        drawRect(ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated)), alpha = 0.035f)
    }

@Composable
private fun rememberNoise(): ImageBitmap = remember {
    val size = 128
    val random = Random(7)
    val pixels = IntArray(size * size) {
        val v = random.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Glass: a faint white fill with a top-lit 1dp edge (white 16% → 4%). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(CarLinkTokens.dimens.gutter),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = CarLinkTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) colors.glassFillPressed else colors.glassFill)
            .border(glassBorder(), shape)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = androidx.compose.material3.ripple(),
                role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
    ) {
        // Glass is not a Material Surface: give its text the theme's color instead of the black default.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

/**
 * Glass's top-lit 1dp edge (white 16% → 4%); in lite rendering a solid hairline of the middle tone.
 */
@Composable
fun glassBorder(width: Dp = 1.dp): BorderStroke {
    val colors = CarLinkTokens.colors
    return if (LocalLiteRendering.current) BorderStroke(width, lerp(colors.glassStrokeTop, colors.glassStrokeBottom, 0.5f))
    else BorderStroke(width, Brush.verticalGradient(listOf(colors.glassStrokeTop, colors.glassStrokeBottom)))
}

/**
 * An endless animation between [from] and [to]. In lite rendering it moves in [liteSteps] steps
 * per cycle, so the screen is redrawn a few times a second instead of on every vsync; the value
 * still reads the same (a charging slot, a pulse).
 */
@Composable
internal fun loopingValue(from: Float, to: Float, spec: InfiniteRepeatableSpec<Float>, label: String, liteSteps: Int = 6): Float {
    val raw = rememberInfiniteTransition(label = label).animateFloat(from, to, spec, label = label)
    if (!LocalLiteRendering.current) return raw.value
    val stepped = remember(raw, liteSteps) {
        derivedStateOf {
            val t = ((raw.value - from) / (to - from)).coerceIn(0f, 1f)
            from + (to - from) * (kotlin.math.round(t * liteSteps) / liteSteps)
        }
    }
    return stepped.value
}

/** Tracked-caps label for glass chip headers only ("CONNECTION", "NETWORK"); never a sentence. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = CarLinkTokens.colors.onSurfaceTertiary) {
    Text(text.uppercase(Locale.ROOT), modifier, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

/** A status chip on glass: eyebrow, a value, an icon and an optional status dot. */
@Composable
fun GlassChip(eyebrow: String, value: String, @DrawableRes icon: Int, modifier: Modifier = Modifier,
              status: Color? = null, onClick: (() -> Unit)? = null) {
    val dimens = CarLinkTokens.dimens
    GlassCard(modifier.defaultMinSize(minHeight = dimens.rowTwoLine), onClick = onClick,
        contentPadding = PaddingValues(horizontal = dimens.gutter, vertical = 16.dp)) {
        // Header (eyebrow + status dot) and value row on fixed lines, so every chip lines up.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(eyebrow, Modifier.weight(1f))
                if (status != null) Box(Modifier.size(10.dp).clip(CircleShape).background(status))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(dimens.iconSecondary), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                // Values wrap rather than truncate (a long phone name takes a second line); the row
                // that holds the chips gives them equal heights.
                Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A cabin tell-tale: a lit dot and a short tracked label in the state's colour ("CONNECTED",
 * "NEEDS ATTENTION"). [pill] seats it on a faint tinted capsule; dense layouts drop the capsule.
 */
@Composable
fun TellTale(text: String, color: Color, modifier: Modifier = Modifier, pill: Boolean = true) {
    val car = CarLinkTokens.profile == com.carlink.app.ui.theme.UiProfile.CAR
    val dot = if (car) 10.dp else 8.dp
    Row(
        modifier.then(if (pill) Modifier.clip(PillShape).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.30f), PillShape)
            .padding(horizontal = if (car) 18.dp else 12.dp, vertical = if (car) 8.dp else 5.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot is "lit": a solid core with a soft halo, like an indicator lamp.
        Box(Modifier.size(dot).drawBehind {
            drawCircle(color.copy(alpha = 0.28f), radius = size.minDimension)
            drawCircle(color)
        })
        Spacer(Modifier.width(if (car) 14.dp else 10.dp))
        Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/**
 * A small rounded tile that seats an icon, tinted by [tint] when it carries a state (Bluetooth on,
 * same Wi-Fi) and neutral otherwise. Used in status cells, setting rows and lists.
 */
@Composable
fun IconTile(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color? = null, size: Dp = CarLinkTokens.dimens.avatarRow) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(tint?.copy(alpha = 0.14f) ?: ink.copy(alpha = 0.06f))
            .border(1.dp, (tint ?: ink).copy(alpha = if (tint != null) 0.22f else 0.08f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.5f), tint = tint ?: ink)
    }
}

/**
 * Solid Ion pill: the one primary action of a state. A top-lit sheen and a hairline highlight
 * make it read as a lit control rather than a flat Material pill (RESEARCH_HOME_V2 §4).
 */
@Composable
fun IonButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lite = LocalLiteRendering.current
    Row(
        modifier
            .defaultMinSize(minHeight = dimens.primaryButton, minWidth = dimens.primaryButton * 2.6f)
            .clip(CircleShape)
            .background(scheme.primary)
            .then(if (lite) Modifier.drawBehind { if (pressed) drawRect(Color.Black.copy(alpha = 0.12f)) } else Modifier
                .drawBehind {
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (pressed) 0f else 0.20f), Color.Transparent),
                        endY = size.height * 0.6f))
                    if (pressed) drawRect(Color.Black.copy(alpha = 0.12f))
                }
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0f))), CircleShape))
            .clickable(interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = dimens.gutter * 1.5f, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(dimens.iconSecondary), tint = scheme.onPrimary)
            Spacer(Modifier.width(14.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary, maxLines = 2)
    }
}

/** A round glass icon button (Back, Settings, Close) at the minimum touch size. */
@Composable
fun RoundGlassIconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    GlassCard(modifier.size(dimens.minTouch), shape = CircleShape, onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Icon(painterResource(icon), contentDescription, Modifier.align(Alignment.Center).size(dimens.iconSecondary * 0.9f),
            tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Glass pill: secondary actions (Cancel, Disconnect, Use a cable). */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val dimens = CarLinkTokens.dimens
    GlassCard(modifier.defaultMinSize(minHeight = dimens.secondaryButton), shape = CircleShape, onClick = onClick,
        contentPadding = PaddingValues(horizontal = dimens.gutter * 1.25f, vertical = 10.dp)) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painterResource(icon), null, Modifier.size(dimens.iconTertiary + 4.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
        }
    }
}

/** Steps of an attempt on one line (Found · Identified · Linked · Picture), active step pulsing. */
@Composable
fun ProgressLine(steps: List<String>, active: Int, failed: Boolean, modifier: Modifier = Modifier, still: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val pulse = if (LocalReduceMotion.current || failed || still) 1f else loopingValue(
        0.45f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "pulse", liteSteps = 3)
    val gap = 12.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Step names go under their segments when every name fits its segment; otherwise one
        // caption names the current step, so no name is ever cut short.
        val segment = constraints.maxWidth.let { (it - (steps.size - 1) * with(LocalDensity.current) { gap.roundToPx() }) / steps.size }
        val labelsFit = steps.all { measurer.measure(it, style, maxLines = 1).size.width <= segment }
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                steps.forEachIndexed { index, label ->
                    val done = index < active
                    val current = index == active
                    val color = when {
                        failed && current -> scheme.error
                        done -> colors.success
                        current -> if (still) colors.warning else scheme.primary
                        // outlineVariant is a hairline color and vanishes on the light canvas; the track needs more.
                        else -> scheme.outline.copy(alpha = 0.45f)
                    }
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                            .background(color.copy(alpha = if (current && !failed) pulse else 1f)))
                        if (labelsFit) {
                            Spacer(Modifier.height(10.dp))
                            Text(label, style = style, maxLines = 1,
                                color = if (done || current) scheme.onSurface else colors.onSurfaceTertiary)
                        }
                    }
                }
            }
            if (!labelsFit) {
                val current = active.coerceIn(0, steps.lastIndex)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.carlink_home_step_of, current + 1, steps.size, steps[current]), style = style,
                    color = scheme.onSurface)
            }
        }
    }
}

/** A round glass disc with an icon, used on the phone's screen and for compact heroes. */
@Composable
fun GlyphDisc(@DrawableRes icon: Int, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f))
        .border(1.dp, tint.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.5f), tint = tint)
    }
}

/** Wraps a [RoundedCornerShape] large enough to read as a pill at any height. */
val PillShape = RoundedCornerShape(50)
