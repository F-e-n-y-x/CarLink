package com.carlink.app.ui.connecting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.Transport
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.Route
import com.carlink.app.ui.components.CarIcon
import com.carlink.app.ui.components.CarTopBar
import com.carlink.app.ui.components.EngineBadge
import com.carlink.app.ui.components.InitialFocus
import com.carlink.app.ui.components.PhoneAvatar
import com.carlink.app.ui.components.PrimaryButton
import com.carlink.app.ui.components.SecondaryButton
import com.carlink.app.ui.components.StatusChip
import com.carlink.app.ui.components.TertiaryButton
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.focusRing
import com.carlink.app.ui.components.formatElapsed
import com.carlink.app.ui.components.isolate
import com.carlink.app.ui.components.kindOf
import com.carlink.app.ui.components.motionMillis
import com.carlink.app.ui.components.phoneKindDescription
import com.carlink.app.ui.components.rememberElapsedMillis
import com.carlink.app.ui.components.tabular
import com.carlink.app.ui.components.transportIcon
import com.carlink.app.ui.components.transportName
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.HeightClass
import com.carlink.app.ui.theme.WidthClass
import kotlinx.coroutines.delay

/**
 * Connecting (DESIGN_SPEC §6.2): makes a 3–25 s wait legible and recoverable. Failure stays on
 * this screen (no modal) with one primary recovery.
 */
@Composable
fun ConnectingScreen(vm: CarLinkViewModel, onBack: () -> Unit, navigate: (Route) -> Unit) {
    val link by vm.link.collectAsStateWithLifecycle()
    val attemptStartedAt by vm.attemptStartedAt.collectAsStateWithLifecycle()
    val tracker = rememberAttemptTracker(link, attemptStartedAt)

    // The timer ticks while the attempt runs and freezes when it connects or fails.
    val start = tracker.startedAt
    val end = tracker.endedAt
    val ticking by rememberElapsedMillis(if (end == null) start else null)
    val elapsed = if (start != null && end != null) end - start else ticking

    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(link.stage) {
        when (link.stage) {
            // The engine's projection comes to the front by itself; Home is what's left behind.
            LinkStage.CONNECTED -> {
                delay(CONNECTED_HOLD_MILLIS)
                currentOnBack()
            }
            // Ended or cancelled elsewhere. A short grace absorbs the Idle blip of a restart.
            LinkStage.IDLE -> {
                delay(IDLE_GRACE_MILLIS)
                currentOnBack()
            }
            else -> Unit
        }
    }

    val actions = remember(vm, onBack, navigate) {
        ConnectingActions(
            cancel = {
                vm.disconnect()
                onBack()
            },
            retry = vm::retry,
            diagnostics = { navigate(Route.DIAGNOSTICS) },
            backHome = onBack,
        )
    }
    ConnectingContent(link, tracker.steps(link), tracker.currentStep, elapsed, actions)
}

private const val CONNECTED_HOLD_MILLIS = 1_500L
private const val IDLE_GRACE_MILLIS = 300L

internal class ConnectingActions(
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val diagnostics: () -> Unit = {},
    val backHome: () -> Unit = {},
)

private enum class ConnectingLayout { TWO_COLUMN, STACKED, COMPACT }

/** Stateless Connecting, so previews can render every stage. */
@Composable
internal fun ConnectingContent(
    link: LinkState,
    steps: List<TimelineStep>,
    currentStep: Int,
    elapsedMillis: Long,
    actions: ConnectingActions,
) {
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens
    val arrangement = when {
        layout.isLandscape && layout.width >= WidthClass.MEDIUM -> ConnectingLayout.TWO_COLUMN
        layout.width == WidthClass.COMPACT -> ConnectingLayout.COMPACT
        else -> ConnectingLayout.STACKED
    }
    val shortHeight = layout.height == HeightClass.COMPACT
    val margin = if (arrangement == ConnectingLayout.COMPACT) dimens.compactMargin else dimens.screenMargin
    var showSheet by rememberSaveable { mutableStateOf(false) }

    // The route host draws no Surface, so set the content color for every Text and Icon here.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxSize()) {
        CarTopBar(
            title = stringResource(
                if (link.stage == LinkStage.FAILED) R.string.carlink_connecting_failed_bar_title else R.string.carlink_connecting_title),
            onBack = actions.cancel,
            actions = { ElapsedClock(elapsedMillis) },
        )
        when (arrangement) {
            ConnectingLayout.TWO_COLUMN -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Row(
                    Modifier.widthIn(max = 1600.dp).fillMaxSize().padding(horizontal = margin),
                    horizontalArrangement = Arrangement.spacedBy(dimens.gutter),
                ) {
                    CenteredScrollColumn(Modifier.weight(1f), margin) {
                        ConnectingHero(link, elapsedMillis,
                            if (shortHeight) HeroSize.SHORT else HeroSize.LARGE, centered = true)
                        Spacer(Modifier.height(if (shortHeight) dimens.gutter else dimens.cardPadding))
                        ConnectingButtons(link.stage, actions, stacked = false, centered = true)
                    }
                    CenteredScrollColumn(Modifier.weight(1f), margin) {
                        StageTimeline(steps)
                    }
                }
            }
            ConnectingLayout.STACKED -> Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = margin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val width = Modifier.widthIn(max = dimens.contentMaxWidth).fillMaxWidth()
                    Spacer(Modifier.height(dimens.gutter))
                    Box(width) { ConnectingHero(link, elapsedMillis, HeroSize.LARGE, centered = true) }
                    Spacer(Modifier.height(dimens.cardPadding))
                    StageTimeline(steps, width)
                    Spacer(Modifier.height(dimens.gutter))
                }
                StickyActions(margin) { ConnectingButtons(link.stage, actions, stacked = false, centered = true) }
            }
            ConnectingLayout.COMPACT -> Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = margin),
                ) {
                    Spacer(Modifier.height(8.dp))
                    ConnectingHero(link, elapsedMillis, HeroSize.COMPACT, centered = false)
                    Spacer(Modifier.height(dimens.gutter))
                    SegmentedProgress(steps, currentStep.coerceIn(1, steps.size.coerceAtLeast(1)), onExpand = { showSheet = true })
                    steps.firstOrNull { it.state == StepState.ACTIVE || it.state == StepState.FAILED }?.let { step ->
                        Spacer(Modifier.height(8.dp))
                        StageRow(step, topConnector = null, bottomConnector = null)
                    }
                    Spacer(Modifier.height(dimens.gutter))
                }
                StickyActions(margin) { ConnectingButtons(link.stage, actions, stacked = true, centered = false) }
            }
        }
    }
    }

    if (showSheet) StepsSheet(steps, onDismiss = { showSheet = false })
}

/** A column that scrolls when it must and centers its content vertically when it fits. */
@Composable
private fun CenteredScrollColumn(modifier: Modifier, bottomMargin: Dp, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = minHeight).padding(bottom = bottomMargin),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

/** Buttons pinned to the bottom in portrait and Compact, so Cancel is always one tap away. */
@Composable
private fun StickyActions(margin: Dp, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = CarLinkTokens.dimens.targetGap)) { content() }
    }
}

@Composable
private fun ElapsedClock(elapsedMillis: Long) {
    val time = formatElapsed(elapsedMillis)
    val spoken = stringResource(R.string.carlink_connecting_elapsed, time)
    Text(
        time,
        style = MaterialTheme.typography.titleMedium.tabular(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = CarLinkTokens.dimens.targetGap).semantics { contentDescription = spoken },
    )
}

private enum class HeroSize { LARGE, SHORT, COMPACT }

/** Expected duration (§6.2.1 fallback): about 20 s for USB CarPlay, about 10 s otherwise. */
private fun expectedSeconds(link: LinkState): Int =
    if (link.engine == Engine.CARPLAY && link.transport == Transport.USB) 20 else 10

/** Avatar, name, engine + transport chips, then the stage's title and subtitle. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectingHero(link: LinkState, elapsedMillis: Long, size: HeroSize, centered: Boolean) {
    val dimens = CarLinkTokens.dimens
    val kind = kindOf(link.engine)
    val name = link.phoneName ?: phoneKindDescription(kind)
    val alignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val avatarSize = when (size) {
        HeroSize.LARGE -> dimens.avatarHero + dimens.avatarHero / 4
        HeroSize.SHORT, HeroSize.COMPACT -> dimens.avatarRow
    }
    val avatarDescription = phoneKindDescription(kind)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        val avatar = @Composable {
            PhoneAvatar(kind, avatarSize, Modifier.semantics { contentDescription = avatarDescription },
                active = link.stage == LinkStage.CONNECTED)
        }
        val chips = @Composable {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, alignment),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                link.engine?.let { EngineBadge(it) }
                link.transport?.let { StatusChip(transportName(it), transportIcon(it)) }
            }
        }
        if (size == HeroSize.COMPACT) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(dimens.gutter))
                Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            chips()
        } else {
            avatar()
            Spacer(Modifier.height(dimens.targetGap))
            Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start)
            Spacer(Modifier.height(12.dp))
            chips()
        }
        Spacer(Modifier.height(if (size == HeroSize.LARGE) dimens.cardPadding else dimens.gutter))

        val duration = motionMillis(CarMotion.MEDIUM)
        val slide = with(LocalDensity.current) { 16.dp.roundToPx() }
        AnimatedContent(
            targetState = link,
            contentKey = { it.stage },
            transitionSpec = {
                (fadeIn(tween(duration, easing = CarMotion.EmphasizedDecelerate)) +
                    slideInVertically(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { slide }) togetherWith
                    fadeOut(tween(duration / 2, easing = CarMotion.EmphasizedAccelerate)) using
                    SizeTransform(clip = false) { _, _ -> tween(duration, easing = CarMotion.EmphasizedDecelerate) }
            },
            label = "connectingTitle",
        ) { state ->
            HeroStatus(state, size, centered)
        }

        val slow = link.isActive && elapsedMillis > expectedSeconds(link) * 2_000L
        AnimatedVisibility(slow, enter = fadeIn(tween(duration)) + expandVertically(tween(duration)),
            exit = fadeOut(tween(duration / 2)) + shrinkVertically(tween(duration))) {
            Text(
                stringResource(R.string.carlink_connecting_slow),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun HeroStatus(link: LinkState, size: HeroSize, centered: Boolean) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val typography = MaterialTheme.typography
    val engine = link.engine?.let { engineName(it) }
    val align = if (centered) TextAlign.Center else TextAlign.Start
    val titleStyle: TextStyle = when (size) {
        HeroSize.LARGE -> if (link.stage == LinkStage.FAILED) typography.headlineMedium else typography.displayMedium
        HeroSize.SHORT, HeroSize.COMPACT -> if (link.stage == LinkStage.FAILED) typography.headlineMedium else typography.headlineLarge
    }
    val (title, subtitle) = when (link.stage) {
        LinkStage.FAILED -> Pair(
            engine?.let { stringResource(R.string.carlink_connecting_failed_title_engine, it) }
                ?: stringResource(R.string.carlink_connecting_failed_title),
            link.detail?.takeIf { it.isNotBlank() }?.let(::isolate)
                ?: stringResource(R.string.carlink_connecting_failed_body_fallback),
        )
        LinkStage.CONNECTED -> Pair(
            stringResource(R.string.carlink_connecting_connected_title),
            stringResource(R.string.carlink_connecting_connected_subtitle, engine.orEmpty()),
        )
        LinkStage.WAITING -> Pair(
            stringResource(R.string.carlink_connecting_waiting_for,
                link.phoneName?.let(::isolate) ?: stringResource(R.string.carlink_home_your_phone)),
            expectedText(link),
        )
        else -> Pair(
            engine?.let { stringResource(R.string.carlink_connecting_starting, it) }
                ?: stringResource(R.string.carlink_connecting_title),
            expectedText(link),
        )
    }
    val titleColor = when (link.stage) {
        LinkStage.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (link.stage) {
                LinkStage.FAILED -> CarIcon(R.drawable.carlink_ic_error_filled, null, size = dimens.iconPrimary,
                    tint = MaterialTheme.colorScheme.error)
                LinkStage.CONNECTED -> CarIcon(R.drawable.carlink_ic_check_circle_filled, null, size = dimens.iconPrimary,
                    tint = colors.success)
                else -> Unit
            }
            if (link.stage == LinkStage.FAILED || link.stage == LinkStage.CONNECTED) Spacer(Modifier.width(12.dp))
            Text(
                title, style = titleStyle, color = titleColor, textAlign = align,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = if (link.stage == LinkStage.FAILED) LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
    }
}

@Composable
private fun expectedText(link: LinkState): String {
    val seconds = expectedSeconds(link)
    return pluralStringResource(R.plurals.carlink_connecting_expected, seconds, seconds)
}

/** Cancel while running; Try again / Diagnostics / Back to home after a failure (§6.2.2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectingButtons(stage: LinkStage, actions: ConnectingActions, stacked: Boolean, centered: Boolean) {
    val dimens = CarLinkTokens.dimens
    val focus = remember { FocusRequester() }
    InitialFocus(focus, key = stage)
    val shape = RoundedCornerShape(dimens.buttonRadius)
    val fill = if (stacked) Modifier.fillMaxWidth() else Modifier
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimens.targetGap, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(dimens.targetGap),
    ) {
        when (stage) {
            LinkStage.FAILED -> {
                PrimaryButton(stringResource(R.string.carlink_connecting_try_again), actions.retry,
                    fill.focusRing(shape).focusRequester(focus), icon = R.drawable.carlink_ic_refresh)
                SecondaryButton(stringResource(R.string.carlink_connecting_diagnostics), actions.diagnostics,
                    fill.focusRing(shape), icon = R.drawable.carlink_ic_monitor_heart)
                TertiaryButton(stringResource(R.string.carlink_connecting_back_home), actions.backHome,
                    fill.focusRing(shape), icon = R.drawable.carlink_ic_home)
            }
            LinkStage.WAITING, LinkStage.CONNECTING -> {
                SecondaryButton(stringResource(R.string.carlink_connecting_cancel), actions.cancel,
                    fill.widthIn(min = if (stacked) 0.dp else dimens.minTouch * 2.5f).focusRing(shape).focusRequester(focus),
                    icon = R.drawable.carlink_ic_close)
            }
            LinkStage.CONNECTED, LinkStage.IDLE -> Unit
        }
    }
}

/** The full timeline from Compact's progress bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepsSheet(steps: List<TimelineStep>, onDismiss: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = dimens.compactMargin, end = dimens.compactMargin, bottom = dimens.gutter),
        ) {
            Text(stringResource(R.string.carlink_connecting_all_steps_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = dimens.targetGap).semantics { heading() })
            StageTimeline(steps)
        }
    }
}
