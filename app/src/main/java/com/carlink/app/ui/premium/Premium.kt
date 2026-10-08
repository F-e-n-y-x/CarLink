package com.carlink.app.ui.premium

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.core.PhoneKind
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.LocalReduceMotion
import java.util.Locale
import kotlin.random.Random

/**
 * Graphite and Ion materials (design/RESEARCH_PREMIUM_UX.md §5–6). Three materials only:
 * Atmosphere (background), Glass (cards, chips) and solid Ion (the primary button). No runtime
 * blur and no shadows: head-unit GPUs are mid-range, so depth comes from light and tone.
 */

/** Full-bleed canvas: one soft light source top-start, an optional state glow, and noise against banding. */
@Composable
fun Atmosphere(modifier: Modifier = Modifier, glow: Color = Color.Transparent, glowCenter: Offset = Offset(0.72f, 0.55f),
               content: @Composable BoxScope.() -> Unit) {
    val colors = CarLinkTokens.colors
    val base = MaterialTheme.colorScheme.background
    val animatedGlow by animateColorAsState(glow, tween(if (LocalReduceMotion.current) 0 else 600), label = "glow")
    val noise = rememberNoise()
    Box(
        modifier
            .background(base)
            .atmosphere(colors.atmosphereLight, colors.atmosphereFill, animatedGlow, glowCenter, noise),
        content = content,
    )
}

private fun Modifier.atmosphere(light: Color, fill: Color, glow: Color, glowCenter: Offset, noise: ImageBitmap) =
    drawBehind {
        val diagonal = kotlin.math.hypot(size.width, size.height)
        // The single light source, top-start, like a cabin light on graphite.
        drawRect(Brush.radialGradient(listOf(light, light.copy(alpha = 0f)),
            center = Offset(size.width * 0.08f, -size.height * 0.1f), radius = diagonal * 0.75f))
        // A broad, faint fill from the bottom-end for depth.
        drawRect(Brush.radialGradient(listOf(fill.copy(alpha = 0.7f), fill.copy(alpha = 0f)),
            center = Offset(size.width, size.height * 1.1f), radius = diagonal * 0.6f))
        if (glow.alpha > 0f) {
            drawRect(Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * 0.45f), glow.copy(alpha = 0f)),
                center = Offset(size.width * glowCenter.x, size.height * glowCenter.y), radius = size.minDimension * 0.62f))
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
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(colors.glassStrokeTop, colors.glassStrokeBottom))), shape)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = androidx.compose.material3.ripple(),
                role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(eyebrow)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(dimens.iconSecondary), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (status != null) {
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(12.dp).clip(CircleShape).background(status))
                }
            }
        }
    }
}

/** Solid Ion pill: the one primary action of a state. */
@Composable
fun IonButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val dimens = CarLinkTokens.dimens
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .defaultMinSize(minHeight = dimens.primaryButton, minWidth = dimens.primaryButton * 2.6f)
            .clip(CircleShape)
            .background(scheme.primary)
            .clickable(role = Role.Button, onClick = onClick)
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

/**
 * The hero: an unbranded handset drawn in code (no image assets), tilted in 3D with a cheap
 * graphicsLayer rotation. [screen] draws live status on its display; [glow] tints the screen.
 */
@Composable
fun HeroPhone(kind: PhoneKind, modifier: Modifier = Modifier, glow: Color = Color.Transparent, tilt: Float = -14f,
              screen: @Composable BoxScope.() -> Unit = {}) {
    val reduceMotion = LocalReduceMotion.current
    val float = if (reduceMotion) 0f else rememberInfiniteTransition(label = "float").animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse), label = "float",
    ).value
    Box(
        modifier
            .aspectRatio(PHONE_ASPECT)
            .graphicsLayer {
                rotationY = tilt
                rotationX = 4f
                cameraDistance = 14f * density
                translationY = float * 6.dp.toPx()
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawPhone(kind, glow) }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // Keep status clear of the bezel and the camera cut-out.
            val bezel = maxWidth * 0.1f
            Box(Modifier.fillMaxSize().padding(start = bezel, end = bezel, top = bezel * 2.2f, bottom = bezel * 1.4f),
                contentAlignment = Alignment.Center, content = screen)
        }
    }
}

private const val PHONE_ASPECT = 0.49f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPhone(kind: PhoneKind, glow: Color) {
    val w = size.width
    val h = size.height
    val radius = w * 0.15f
    // Contact shadow (baked into the drawing; there are no UI shadows).
    drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
        center = Offset(w / 2, h * 1.01f), radius = w * 0.55f), topLeft = Offset(-w * 0.05f, h * 0.95f), size = Size(w * 1.1f, h * 0.12f))
    // Graphite body with a rim light from the top-start.
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF3A3F47), Color(0xFF1A1D22), Color(0xFF101216)),
        start = Offset(0f, 0f), end = Offset(w, h)), cornerRadius = CornerRadius(radius))
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.18f)),
        start = Offset(0f, 0f), end = Offset(w, h)), cornerRadius = CornerRadius(radius), style = Stroke(width = w * 0.012f))
    // Side buttons.
    val button = Color(0xFF2A2E35)
    drawRoundRect(button, Offset(-w * 0.012f, h * 0.2f), Size(w * 0.018f, h * 0.07f), CornerRadius(w * 0.01f))
    drawRoundRect(button, Offset(-w * 0.012f, h * 0.3f), Size(w * 0.018f, h * 0.07f), CornerRadius(w * 0.01f))
    drawRoundRect(button, Offset(w * 0.994f, h * 0.26f), Size(w * 0.018f, h * 0.11f), CornerRadius(w * 0.01f))
    // Display.
    val inset = w * 0.045f
    val screenSize = Size(w - inset * 2, h - inset * 2)
    val screenRadius = CornerRadius(radius - inset * 0.8f)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF0D1218), Color(0xFF05070A))), Offset(inset, inset), screenSize, screenRadius)
    if (glow.alpha > 0f) {
        drawRoundRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.38f), Color.Transparent),
            center = Offset(w / 2, h * 0.62f), radius = w * 0.75f), Offset(inset, inset), screenSize, screenRadius)
    }
    // Glass reflection across the display.
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
        start = Offset(inset, inset), end = Offset(w * 0.75f, h * 0.45f)), Offset(inset, inset), screenSize, screenRadius)
    // Camera: an island for iPhone, a punch hole for Android, a neutral pill when unknown.
    val camY = inset + h * 0.028f
    when (kind) {
        PhoneKind.ANDROID -> drawCircle(Color.Black, radius = w * 0.028f, center = Offset(w / 2, camY + w * 0.01f))
        else -> drawRoundRect(Color.Black, Offset(w * 0.34f, camY - w * 0.01f), Size(w * 0.32f, w * 0.085f), CornerRadius(w * 0.05f))
    }
}

/** Steps of an attempt on one line (Found · Identified · Linked · Picture), active step pulsing. */
@Composable
fun ProgressLine(steps: List<String>, active: Int, failed: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val pulse = if (LocalReduceMotion.current || failed) 1f else rememberInfiniteTransition(label = "pulse").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "pulse").value
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { index, label ->
            val done = index < active
            val current = index == active
            val color = when {
                failed && current -> scheme.error
                done -> colors.success
                current -> scheme.primary
                else -> scheme.outlineVariant
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                    .background(color.copy(alpha = if (current && !failed) pulse else 1f)))
                Spacer(Modifier.height(10.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (done || current) scheme.onSurface else colors.onSurfaceTertiary)
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
