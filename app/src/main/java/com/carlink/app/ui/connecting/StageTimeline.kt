package com.carlink.app.ui.connecting

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.ui.components.CarIcon
import com.carlink.app.ui.components.ltr
import com.carlink.app.ui.components.motionMillis
import com.carlink.app.ui.components.tabular
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.UiProfile
import java.util.Locale

/** A timeline row's state; each has its own glyph, so color is never the only signal (§7.1). */
internal enum class StepState { DONE, ACTIVE, PENDING, FAILED }

@Immutable
internal data class TimelineStep(
    val number: Int,
    val label: String,
    val state: StepState,
    /** How long the step took, once done; null if unknown (skipped or never timed). */
    val durationMillis: Long?,
    /** One line the driver can act on, shown on the active row only. */
    val hint: String?,
)

/** Default number of steps when the engine hasn't said (both engines report 6). */
internal const val DEFAULT_STEP_COUNT = 6

/** Engine-aware step labels (1-based) for CarPlay, Android Auto, or an engine not known yet. */
@StringRes
internal fun stepLabel(engine: Engine?, transport: Transport?, step: Int, count: Int): Int {
    if (step >= count) return R.string.carlink_step_connected
    return when (engine) {
        Engine.CARPLAY -> when (step) {
            1 -> R.string.carlink_step_cp_preparing
            2 -> R.string.carlink_step_cp_finding
            3 -> if (transport == Transport.USB) R.string.carlink_step_cp_usb_link else R.string.carlink_step_cp_joining_wifi
            4 -> R.string.carlink_step_cp_pairing
            else -> R.string.carlink_step_cp_starting
        }
        Engine.ANDROID_AUTO -> when (step) {
            1 -> R.string.carlink_step_aa_waiting
            2 -> R.string.carlink_step_aa_found
            3 -> R.string.carlink_step_aa_linked
            4 -> R.string.carlink_step_aa_handshake
            else -> R.string.carlink_step_aa_picture
        }
        null -> when (step) {
            1 -> R.string.carlink_step_preparing
            2 -> R.string.carlink_step_finding
            3 -> R.string.carlink_step_linking
            4 -> R.string.carlink_step_handshake
            else -> R.string.carlink_step_picture
        }
    }
}

/**
 * Remembers what the engine's [LinkState] forgets: the step an attempt failed at (FAILED carries
 * step 0), the step count, and when each step started, so finished steps can show durations.
 */
@Stable
internal class AttemptTracker {
    var currentStep by mutableIntStateOf(0)
        private set
    var stepCount by mutableIntStateOf(DEFAULT_STEP_COUNT)
        private set
    /** elapsedRealtime when the attempt started (latched; the ViewModel clears it on CONNECTED). */
    var startedAt by mutableStateOf<Long?>(null)
        private set
    /** elapsedRealtime when the attempt ended (connected or failed); freezes the timer. */
    var endedAt by mutableStateOf<Long?>(null)
        private set
    private val stepStartedAt = mutableStateMapOf<Int, Long>()
    val durations = mutableStateMapOf<Int, Long>()

    fun update(link: LinkState, attemptStartedAt: Long?, now: Long = SystemClock.elapsedRealtime()) {
        if (attemptStartedAt != null && attemptStartedAt != startedAt) {
            // A new attempt (tap, retry or a USB plug-in) starts a fresh timeline.
            reset()
            startedAt = attemptStartedAt
        }
        if (link.stepCount > 0) stepCount = link.stepCount
        when (link.stage) {
            LinkStage.WAITING, LinkStage.CONNECTING -> {
                endedAt = null
                val step = link.step.coerceAtLeast(1)
                if (step < currentStep) {
                    reset()
                    startedAt = attemptStartedAt ?: now
                }
                if (step != currentStep) {
                    finish(currentStep, now)
                    stepStartedAt[step] = if (currentStep == 0) startedAt ?: now else now
                    currentStep = step
                }
                if (startedAt == null) startedAt = now
            }
            LinkStage.CONNECTED -> {
                if (endedAt == null) {
                    finish(currentStep, now)
                    endedAt = now
                }
                currentStep = stepCount
            }
            LinkStage.FAILED -> {
                if (endedAt == null) endedAt = now
                if (currentStep == 0) currentStep = 1
            }
            LinkStage.IDLE -> Unit
        }
    }

    private fun finish(step: Int, now: Long) {
        if (step <= 0) return
        stepStartedAt[step]?.let { durations[step] = (now - it).coerceAtLeast(0) }
    }

    private fun reset() {
        currentStep = 0
        endedAt = null
        stepStartedAt.clear()
        durations.clear()
    }

    /** The rows to draw for [link]. */
    @Composable
    fun steps(link: LinkState): List<TimelineStep> {
        val count = stepCount
        val active = currentStep.coerceIn(1, count)
        return (1..count).map { n ->
            val state = when (link.stage) {
                LinkStage.CONNECTED -> StepState.DONE
                LinkStage.FAILED -> when {
                    n < active -> StepState.DONE
                    n == active -> StepState.FAILED
                    else -> StepState.PENDING
                }
                else -> when {
                    n < active -> StepState.DONE
                    n == active -> StepState.ACTIVE
                    else -> StepState.PENDING
                }
            }
            val hint = if (state != StepState.ACTIVE) null else when {
                link.transport == Transport.USB && (n == 2 || n == 3) -> stringResource(R.string.carlink_connecting_hint_allow)
                link.transport == Transport.WIRELESS && link.stage == LinkStage.WAITING ->
                    stringResource(R.string.carlink_connecting_hint_wireless)
                else -> null
            }
            TimelineStep(n, stringResource(stepLabel(link.engine, link.transport, n, count)), state, durations[n], hint)
        }
    }
}

@Composable
internal fun rememberAttemptTracker(link: LinkState, attemptStartedAt: Long?): AttemptTracker {
    // Updated synchronously on the first frame so the timeline never flashes an empty state.
    val tracker = remember { AttemptTracker().also { it.update(link, attemptStartedAt) } }
    LaunchedEffect(link, attemptStartedAt) { tracker.update(link, attemptStartedAt) }
    return tracker
}

/** "0.4 s" / "12 s", Western digits, LTR (§7.3). */
@Composable
internal fun formatDuration(millis: Long): String {
    val seconds = millis / 1000.0
    val number = if (seconds < 10) String.format(Locale.ROOT, "%.1f", seconds) else String.format(Locale.ROOT, "%d", millis / 1000)
    return stringResource(R.string.carlink_connecting_duration, ltr(number))
}

/**
 * The vertical stage timeline (§6.2.1): done rows with a check and duration, the active row on
 * primaryContainer with the progress ring and an optional hint, pending rows at 56 % emphasis,
 * joined by a 4dp connector that turns success once a stage is done.
 */
@Composable
internal fun StageTimeline(steps: List<TimelineStep>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            StageRow(
                step,
                topConnector = if (index == 0) null else steps[index - 1].state == StepState.DONE,
                bottomConnector = if (index == steps.lastIndex) null else step.state == StepState.DONE,
            )
        }
    }
}

/**
 * One timeline row. [topConnector] / [bottomConnector]: null draws no segment, true a done
 * (success) segment, false a pending one.
 */
@Composable
internal fun StageRow(step: TimelineStep, topConnector: Boolean?, bottomConnector: Boolean?, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val duration = motionMillis(CarMotion.MEDIUM)
    val emphasized = step.state == StepState.ACTIVE || step.state == StepState.FAILED
    val background by animateColorAsState(
        when (step.state) {
            StepState.ACTIVE -> scheme.primaryContainer
            StepState.FAILED -> scheme.errorContainer
            else -> Color.Transparent
        },
        tween(duration, easing = CarMotion.EmphasizedDecelerate), label = "stageBackground",
    )
    val labelColor = when (step.state) {
        StepState.DONE -> scheme.onSurface
        StepState.ACTIVE -> scheme.onPrimaryContainer
        StepState.FAILED -> scheme.onErrorContainer
        StepState.PENDING -> colors.onSurfaceTertiary
    }
    val stateText = stringResource(
        when (step.state) {
            StepState.DONE -> R.string.carlink_connecting_state_done
            StepState.ACTIVE -> R.string.carlink_connecting_state_active
            StepState.PENDING -> R.string.carlink_connecting_state_pending
            StepState.FAILED -> R.string.carlink_connecting_state_failed
        },
    )
    val spoken = stringResource(R.string.carlink_connecting_step_semantics, step.label, stateText)
    val connectorWidth = if (CarLinkTokens.profile == UiProfile.CAR) 4.dp else 3.dp
    val doneColor = colors.success
    val pendingColor = scheme.outlineVariant
    val iconSize = dimens.iconSecondary
    val gutter = 12.dp

    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = if (emphasized) dimens.rowTwoLine else dimens.rowOneLine * 0.8f)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (emphasized) liveRegion = LiveRegionMode.Polite
            }
            .padding(end = dimens.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon column with its connector segments (mirrors to the end edge in RTL automatically).
        Box(
            Modifier
                .width(iconSize + gutter * 2)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    val half = iconSize.toPx() / 2 + 4.dp.toPx()
                    val cy = size.height / 2
                    val stroke = connectorWidth.toPx()
                    topConnector?.let { done ->
                        drawLine(if (done) doneColor else pendingColor, Offset(x, 0f), Offset(x, cy - half), stroke)
                    }
                    bottomConnector?.let { done ->
                        drawLine(if (done) doneColor else pendingColor, Offset(x, cy + half), Offset(x, size.height), stroke)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            StageGlyph(step.state, iconSize)
        }
        Column(Modifier.weight(1f).padding(vertical = 12.dp).clearAndSetSemantics { }) {
            Text(
                step.label,
                style = MaterialTheme.typography.bodyLarge.let { if (emphasized) it.copy(fontWeight = FontWeight.Medium) else it },
                color = labelColor,
            )
            if (step.hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(step.hint, style = MaterialTheme.typography.bodyMedium,
                    color = if (step.state == StepState.ACTIVE) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
            }
        }
        if (step.state == StepState.DONE && step.durationMillis != null) {
            Spacer(Modifier.width(dimens.targetGap))
            Text(formatDuration(step.durationMillis), style = MaterialTheme.typography.bodyMedium.tabular(),
                color = colors.onSurfaceTertiary, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}

@Composable
private fun StageGlyph(state: StepState, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    val colors = CarLinkTokens.colors
    when (state) {
        StepState.DONE -> CarIcon(R.drawable.carlink_ic_check_circle_filled, null, size = size, tint = colors.success)
        StepState.FAILED -> CarIcon(R.drawable.carlink_ic_error_filled, null, size = size, tint = scheme.error)
        StepState.PENDING -> CarIcon(R.drawable.carlink_ic_radio_button_unchecked, null, size = size, tint = colors.onSurfaceTertiary)
        StepState.ACTIVE -> if (LocalReduceMotion.current) {
            // The one looping animation becomes a static glyph when animations are off (§5.7).
            CarIcon(R.drawable.carlink_ic_radio_button_checked, null, size = size, tint = scheme.primary)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(size).padding(2.dp),
                color = scheme.primary,
                trackColor = Color.Transparent,
                strokeWidth = if (CarLinkTokens.profile == UiProfile.CAR) 4.dp else 3.dp,
            )
        }
    }
}

/**
 * Compact progress (§6.2.3): "Step n of m" with a segmented bar; the whole block is one
 * target that opens the full timeline.
 */
@Composable
internal fun SegmentedProgress(steps: List<TimelineStep>, current: Int, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val scheme = MaterialTheme.colorScheme
    val expandLabel = stringResource(R.string.carlink_connecting_show_all_steps)
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClickLabel = expandLabel, onClick = onExpand)
            .heightIn(min = dimens.minTouch)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.carlink_connecting_step_of, current, steps.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            CarIcon(R.drawable.carlink_ic_expand_more, null, tint = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEach { step ->
                val color = when (step.state) {
                    StepState.DONE -> colors.success
                    StepState.ACTIVE -> scheme.primary
                    StepState.FAILED -> scheme.error
                    StepState.PENDING -> scheme.surfaceContainerHighest
                }
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}
