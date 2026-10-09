package com.carlink.app.ui.premium

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalLiteRendering
import com.carlink.app.ui.theme.LocalReduceMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the link dial reads (design/RESEARCH_HOME_V2.md §4, "Instrument").
 *
 * @property color the state's light: Ion while ready or connecting, Live when connected, Fault on a failure.
 * @property progress how much of the gauge is lit, 0–1 (ignored when [segments] > 0).
 * @property segments the steps of an attempt drawn as separate arcs; [active] is the step in progress.
 * @property armed a quiet full ring: a phone is known and the link is ready to start.
 * @property dimmed a failed attempt: the lit part is drawn at reduced strength.
 * @property still a held attempt: the active step's slot is marked but nothing charges.
 */
data class DialReading(
    val color: Color,
    val progress: Float = 0f,
    val segments: Int = 0,
    val active: Int = -1,
    val armed: Boolean = false,
    val dimmed: Boolean = false,
    /** The attempt is held: the [active] step's slot is marked but does not charge. */
    val still: Boolean = false,
)

/** The gauge opens at the bottom, like a cluster dial: it starts at 7:30 and sweeps 270° clockwise. */
private const val START = 135f
private const val SWEEP = 270f
private const val TICK_STEP = 5f

/**
 * Home's focal object: a gauge-like instrument drawn in code. A fine tick bezel, a hairline
 * orbit, a progress track and a lit core disc that holds [core] (the state glyph and reading).
 * The stage light behind it belongs to the [Atmosphere] (so no container clips it). Everything
 * is a handful of strokes and two gradients: no blur, no shadows, no bitmaps (head-unit GPUs).
 */
@Composable
fun LinkDial(reading: DialReading, modifier: Modifier = Modifier, core: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    val reduceMotion = LocalReduceMotion.current
    val dark = scheme.background.luminance() < 0.5f
    val color by animateColorAsState(reading.color, tween(if (reduceMotion) 0 else 600), label = "dial")
    val progress by animateFloatAsState(reading.progress, tween(if (reduceMotion) 0 else 700, easing = CarMotion.Standard),
        label = "progress")
    // The step in progress charges its slot over and over; a still image shows it half charged.
    val pulse = when {
        reading.segments == 0 -> 1f
        reading.still -> 0f
        reduceMotion -> 0.55f
        // Lite rendering charges in eight steps (5 redraws a second instead of 60).
        else -> loopingValue(0.06f, 1f, infiniteRepeatable(tween(1600, easing = CarMotion.Standard), RepeatMode.Restart),
            label = "charge", liteSteps = 8)
    }
    val lite = LocalLiteRendering.current
    val ink = scheme.onSurface
    val coreLift = if (dark) scheme.surfaceContainerHighest else scheme.surfaceContainerLowest
    val coreBase = if (dark) scheme.surfaceContainerLow else scheme.surfaceContainerHigh
    val strokeTop = colors.glassStrokeTop
    val strokeBottom = colors.glassStrokeBottom

    Layout(
        content = {
            Canvas(Modifier.fillMaxSize()) {
                drawDial(reading, color, progress, pulse, ink, coreLift, coreBase, strokeTop, strokeBottom, dark, lite)
            }
            // The core disc's content area: a square inside the disc, so text never touches its edge.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = core)
        },
        modifier = modifier.aspectRatio(1f),
    ) { measurables, constraints ->
        val side = minOf(constraints.maxWidth, constraints.maxHeight)
        val dial = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(side, side))
        // The disc has radius 0.6r; its inscribed square is 0.6r·√2 ≈ 0.85r wide.
        val inner = (side * 0.42f).toInt()
        val coreP = measurables[1].measure(androidx.compose.ui.unit.Constraints.fixed(inner, inner))
        layout(side, side) {
            dial.place(0, 0)
            coreP.place((side - inner) / 2, (side - inner) / 2)
        }
    }
}

private fun DrawScope.drawDial(
    reading: DialReading, color: Color, progress: Float, pulse: Float, ink: Color,
    coreLift: Color, coreBase: Color, strokeTop: Color, strokeBottom: Color, dark: Boolean, lite: Boolean,
) {
    val r = size.minDimension / 2
    val c = center
    val hair = 1.dp.toPx()
    val lit = color.copy(alpha = color.alpha * if (reading.dimmed) 0.5f else 1f)
    val litFraction = when {
        reading.segments > 0 -> (reading.active.coerceAtLeast(0).toFloat() / reading.segments)
        else -> progress
    }

    // 1. Tick bezel: a fine tick every 5°, a major one every 45°; ticks inside the lit part glow.
    val ticks = (SWEEP / TICK_STEP).toInt()
    for (i in 0..ticks) {
        val fraction = i / ticks.toFloat()
        val major = i % 9 == 0
        val angle = Math.toRadians((START + SWEEP * fraction).toDouble())
        val outer = r * 0.985f
        val inner = outer - r * if (major) 0.075f else 0.04f
        val on = fraction <= litFraction + 0.0001f && litFraction > 0f
        val tick = when {
            on -> lit.copy(alpha = lit.alpha * if (major) 1f else 0.8f)
            else -> ink.copy(alpha = if (major) 0.34f else if (dark) 0.16f else 0.2f)
        }
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(tick, c + dir * inner, c + dir * outer, strokeWidth = if (major) hair * 2f else hair * 1.25f,
            cap = StrokeCap.Round)
    }

    // 2. Hairline orbit.
    drawCircle(ink.copy(alpha = if (dark) 0.09f else 0.12f), radius = r * 0.86f, center = c, style = Stroke(hair))

    // 3. Progress track and its lit part, with a soft bloom under the lit arc.
    val trackR = r * 0.775f
    val trackW = (r * 0.04f).coerceAtLeast(3.dp.toPx())
    val arcTopLeft = Offset(c.x - trackR, c.y - trackR)
    val arcSize = Size(trackR * 2, trackR * 2)
    drawArc(ink.copy(alpha = if (dark) 0.08f else 0.10f), START, SWEEP, false, arcTopLeft, arcSize,
        style = Stroke(trackW, cap = StrokeCap.Round))
    // Round caps reach past an arc's ends by half the stroke; arcs are shortened by that much so
    // segment gaps stay open.
    val capDeg = Math.toDegrees((trackW / 2f / trackR).toDouble()).toFloat()
    fun litArc(start: Float, sweep: Float, alpha: Float, bloom: Boolean = true) {
        if (sweep <= 0.5f) return
        if (bloom && !reading.dimmed && !lite) {
            drawArc(lit.copy(alpha = lit.alpha * alpha * if (dark) 0.22f else 0.14f), start, sweep, false, arcTopLeft, arcSize,
                style = Stroke(trackW * if (dark) 3.2f else 2.4f, cap = StrokeCap.Round))
        }
        drawArc(lit.copy(alpha = lit.alpha * alpha), start, sweep, false, arcTopLeft, arcSize,
            style = Stroke(trackW, cap = StrokeCap.Round))
    }
    when {
        reading.segments > 0 -> {
            val gap = 5f + capDeg * 2
            val each = (SWEEP - gap * (reading.segments - 1)) / reading.segments
            for (i in 0 until reading.segments) {
                val start = START + i * (each + gap)
                when {
                    i < reading.active -> litArc(start, each, 1f)
                    i == reading.active -> {
                        // The step in progress: its slot glows faintly and charges up (static at 55% without motion).
                        litArc(start, each, 0.28f, bloom = false)
                        litArc(start, each * pulse, 1f)
                    }
                }
            }
        }
        reading.armed -> drawArc(lit.copy(alpha = lit.alpha * 0.45f), START, SWEEP, false, arcTopLeft, arcSize,
            style = Stroke(trackW, cap = StrokeCap.Round))
        else -> litArc(START, SWEEP * progress, 1f)
    }

    // 4. Core disc: lit from above, with a top-lit hairline edge like glass.
    val coreR = r * 0.6f
    if (lite) {
        // Lite rendering: the disc's mid tone as one solid fill.
        drawCircle(androidx.compose.ui.graphics.lerp(coreLift, coreBase, 0.5f), radius = coreR, center = c)
    } else {
        drawCircle(Brush.radialGradient(listOf(coreLift, coreBase), center = Offset(c.x, c.y - coreR * 0.55f),
            radius = coreR * 1.5f), radius = coreR, center = c)
    }
    drawCircle(lit.copy(alpha = lit.alpha * if (dark) 0.10f else 0.07f), radius = coreR, center = c)
    if (lite) {
        drawCircle(androidx.compose.ui.graphics.lerp(strokeTop, strokeBottom, 0.5f), radius = coreR, center = c, style = Stroke(hair))
    } else {
        drawCircle(Brush.verticalGradient(listOf(strokeTop, strokeBottom), startY = c.y - coreR, endY = c.y + coreR),
            radius = coreR, center = c, style = Stroke(hair))
    }
    // A faint inner ring gives the disc a machined edge.
    drawCircle(ink.copy(alpha = if (dark) 0.05f else 0.06f), radius = coreR * 0.9f, center = c, style = Stroke(hair))
}
