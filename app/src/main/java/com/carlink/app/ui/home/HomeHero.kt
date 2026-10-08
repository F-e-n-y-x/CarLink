package com.carlink.app.ui.home

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.Route
import com.carlink.app.ui.components.CarIcon
import com.carlink.app.ui.components.EngineBadge
import com.carlink.app.ui.components.InitialFocus
import com.carlink.app.ui.components.PhoneAvatar
import com.carlink.app.ui.components.PrimaryButton
import com.carlink.app.ui.components.SecondaryButton
import com.carlink.app.ui.components.StatusChip
import com.carlink.app.ui.components.TertiaryButton
import com.carlink.app.ui.components.detectedLabel
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

/** The hero's states (DESIGN_SPEC §6.1.1, hero states table). */
internal enum class HeroKind { READY, WAITING, BLUETOOTH_OFF, NO_PERMISSION, NO_BLUETOOTH, CONNECTING, LIVE, FAILED }

/** How much room the hero has (DESIGN_SPEC §6.1.1–6.1.3). */
internal enum class HeroVariant {
    /** Two columns: start-aligned, display title. */
    LANDSCAPE,

    /** Two columns on a short window (landscape phone): same, smaller title. */
    LANDSCAPE_SHORT,

    /** Stacked on a medium-width window: centered, the largest title. */
    PORTRAIT,

    /** Narrow (⅓ split, phone portrait): condensed. */
    COMPACT,
}

/**
 * A snapshot of everything the hero renders, so the outgoing state keeps its own content while
 * it cross-fades out.
 */
@Immutable
internal data class HeroModel(
    val kind: HeroKind,
    /** The phone the hero is about: the session's, else the default one. */
    val phoneName: String?,
    val phoneKind: PhoneKind,
    val engine: Engine?,
    val transport: Transport?,
    val detail: String?,
    val connectedSince: Long?,
    val defaultPhone: PairedPhone?,
    val hasUnknownPhones: Boolean,
)

internal fun heroModel(home: HomeState): HeroModel {
    val link = home.link
    val kind = when {
        link.stage == LinkStage.CONNECTED -> HeroKind.LIVE
        link.stage == LinkStage.FAILED -> HeroKind.FAILED
        link.stage == LinkStage.WAITING || link.stage == LinkStage.CONNECTING -> HeroKind.CONNECTING
        home.bluetooth == BluetoothAvailability.NO_PERMISSION -> HeroKind.NO_PERMISSION
        home.bluetooth == BluetoothAvailability.OFF -> HeroKind.BLUETOOTH_OFF
        home.bluetooth == BluetoothAvailability.UNSUPPORTED -> HeroKind.NO_BLUETOOTH
        home.defaultPhone != null -> HeroKind.READY
        else -> HeroKind.WAITING
    }
    val session = kind == HeroKind.LIVE || kind == HeroKind.FAILED || kind == HeroKind.CONNECTING
    return HeroModel(
        kind = kind,
        phoneName = if (session) link.phoneName else home.defaultPhone?.name,
        phoneKind = if (session) kindOf(link.engine) else home.defaultPhone?.kind ?: PhoneKind.UNKNOWN,
        engine = if (session) link.engine else home.defaultPhone?.kind?.engine,
        transport = if (session) link.transport else null,
        detail = link.detail,
        connectedSince = home.connectedSince,
        defaultPhone = home.defaultPhone,
        hasUnknownPhones = home.phones.any { it.kind == PhoneKind.UNKNOWN },
    )
}

/** Intents the hero and phones panel raise; [HomeScreen] binds them to the ViewModel. */
internal class HomeActions(
    val connect: (PairedPhone) -> Unit = {},
    val switchTo: (PairedPhone) -> Unit = {},
    val setPhoneKind: (PairedPhone, PhoneKind) -> Unit = { _, _ -> },
    val returnToProjection: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val retry: () -> Unit = {},
    val refreshPhones: () -> Unit = {},
    val resumeAutoConnect: () -> Unit = {},
    val openBluetoothSettings: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
    val navigate: (Route) -> Unit = {},
)

/**
 * The status-first hero card: phone identity, one title, one subtitle, one primary action,
 * at most one secondary, plus the quiet "paused" hint.
 */
@Composable
internal fun HeroCard(
    model: HeroModel,
    connection: ConnectionPrefs,
    variant: HeroVariant,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val dimens = CarLinkTokens.dimens
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val padding = if (variant == HeroVariant.COMPACT || variant == HeroVariant.LANDSCAPE_SHORT) dimens.gutter else dimens.cardPadding
    val duration = motionMillis(CarMotion.MEDIUM)
    val slide = with(LocalDensity.current) { 16.dp.roundToPx() }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (light) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        // Surface passes its min height down, so a tall landscape card centers its content.
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.Center) {
            AnimatedContent(
                targetState = model,
                contentKey = { it.kind },
                transitionSpec = {
                    (fadeIn(tween(duration, easing = CarMotion.EmphasizedDecelerate)) +
                        slideInVertically(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { slide }) togetherWith
                        (fadeOut(tween(duration / 2, easing = CarMotion.EmphasizedAccelerate)) +
                            slideOutVertically(tween(duration, easing = CarMotion.EmphasizedAccelerate)) { -slide }) using
                        SizeTransform(clip = false) { _, _ -> tween(duration, easing = CarMotion.EmphasizedDecelerate) }
                },
                label = "hero",
            ) { state ->
                HeroBody(state, variant, actions)
            }
            val showPaused = connection.autoConnectPaused &&
                model.kind != HeroKind.LIVE && model.kind != HeroKind.CONNECTING
            AnimatedVisibility(
                visible = showPaused,
                enter = fadeIn(tween(duration)) + expandVertically(tween(duration)),
                exit = fadeOut(tween(duration / 2)) + shrinkVertically(tween(duration)),
            ) {
                PausedHint(actions.resumeAutoConnect, centered = variant == HeroVariant.PORTRAIT)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroBody(model: HeroModel, variant: HeroVariant, actions: HomeActions) {
    val dimens = CarLinkTokens.dimens
    val centered = variant == HeroVariant.PORTRAIT
    val compact = variant == HeroVariant.COMPACT
    val content = heroContent(model, compact)
    val primaryFocus = remember { FocusRequester() }
    InitialFocus(primaryFocus, key = model.kind)

    val blockGap = when (variant) {
        HeroVariant.LANDSCAPE, HeroVariant.PORTRAIT -> dimens.cardPadding
        HeroVariant.LANDSCAPE_SHORT, HeroVariant.COMPACT -> dimens.gutter
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        HeroIdentity(model, variant)
        Spacer(Modifier.height(blockGap))

        // Title: the one line a driver reads at a glance.
        val titleColor = if (model.kind == HeroKind.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (model.kind == HeroKind.FAILED) {
                CarIcon(R.drawable.carlink_ic_error_filled, null, size = dimens.iconPrimary, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                content.title,
                style = titleStyle(variant, model.kind),
                color = titleColor,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }
        Spacer(Modifier.height(dimens.targetGap / 2))
        HeroSubtitle(model, content.subtitle, centered)

        Spacer(Modifier.height(blockGap))
        val fill = if (centered || compact) Modifier.fillMaxWidth() else Modifier
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.targetGap, if (centered) Alignment.CenterHorizontally else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(dimens.targetGap),
        ) {
            val buttonShape = RoundedCornerShape(dimens.buttonRadius)
            content.primary?.let { action ->
                PrimaryButton(action.label, action.onClick(actions),
                    fill.focusRing(buttonShape).focusRequester(primaryFocus), icon = action.icon)
            }
            content.tonal?.let { action ->
                val focus = if (content.primary == null) Modifier.focusRequester(primaryFocus) else Modifier
                SecondaryButton(action.label, action.onClick(actions), fill.focusRing(buttonShape).then(focus), icon = action.icon)
            }
            content.text?.let { action ->
                TertiaryButton(action.label, action.onClick(actions), Modifier.focusRing(buttonShape), icon = action.icon)
            }
        }
        content.hint?.let { hint ->
            Spacer(Modifier.height(dimens.targetGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarIcon(hint.icon, null, size = dimens.iconTertiary, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(hint.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Avatar + name + what CarLink detected, or a status glyph when no phone can be shown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroIdentity(model: HeroModel, variant: HeroVariant) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val statusGlyph: Pair<Int, Color>? = when (model.kind) {
        HeroKind.BLUETOOTH_OFF -> R.drawable.carlink_ic_bluetooth_disabled to colors.warning
        HeroKind.NO_PERMISSION -> R.drawable.carlink_ic_lock to colors.warning
        HeroKind.NO_BLUETOOTH -> R.drawable.carlink_ic_usb to MaterialTheme.colorScheme.onSurfaceVariant
        else -> null
    }
    val avatarSize = when (variant) {
        HeroVariant.PORTRAIT -> dimens.avatarHero + dimens.avatarHero / 4
        HeroVariant.LANDSCAPE -> dimens.avatarHero
        HeroVariant.LANDSCAPE_SHORT, HeroVariant.COMPACT -> dimens.avatarRow
    }
    if (statusGlyph != null) {
        Box(
            Modifier.size(avatarSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { CarIcon(statusGlyph.first, null, size = avatarSize * 0.5f, tint = statusGlyph.second) }
        return
    }
    val hasPhone = model.kind != HeroKind.WAITING
    val name = model.phoneName ?: if (hasPhone) phoneKindDescription(model.phoneKind) else null
    val avatarDescription = phoneKindDescription(model.phoneKind)
    val centered = variant == HeroVariant.PORTRAIT
    val avatar = @Composable {
        PhoneAvatar(
            if (hasPhone) model.phoneKind else PhoneKind.UNKNOWN, avatarSize,
            Modifier.semantics { contentDescription = avatarDescription },
            active = model.kind == HeroKind.LIVE,
        )
    }
    val badges = @Composable {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            model.engine?.let { EngineBadge(it) }
            model.transport?.let { StatusChip(transportName(it), transportIcon(it)) }
        }
    }
    if (centered) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            avatar()
            if (name != null) {
                Spacer(Modifier.height(dimens.targetGap))
                Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detectedLabel(model.phoneKind), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                badges()
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            avatar()
            if (name != null) {
                Spacer(Modifier.width(dimens.gutter))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(detectedLabel(model.phoneKind), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (name != null) {
            Spacer(Modifier.height(12.dp))
            badges()
        }
    }
}

/** The subtitle; in the live state it carries the ticking session time. */
@Composable
private fun HeroSubtitle(model: HeroModel, subtitle: String, centered: Boolean) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    val style = MaterialTheme.typography.bodyLarge
    if (model.kind == HeroKind.LIVE) {
        val elapsed by rememberElapsedMillis(model.connectedSince)
        val engine = model.engine?.let { engineName(it) }.orEmpty()
        val transport = model.transport?.let { transportName(it) }.orEmpty()
        Text(
            stringResource(R.string.carlink_home_live_subtitle, engine, transport, formatElapsed(elapsed)),
            style = style.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align,
        )
    } else {
        Text(subtitle, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun titleStyle(variant: HeroVariant, kind: HeroKind): TextStyle {
    val t = MaterialTheme.typography
    return when (variant) {
        HeroVariant.LANDSCAPE -> if (kind == HeroKind.LIVE) t.displayLarge else t.displayMedium
        HeroVariant.PORTRAIT -> t.displayLarge
        HeroVariant.LANDSCAPE_SHORT, HeroVariant.COMPACT -> t.headlineLarge
    }
}

/** "Automatic connection is paused" + Resume: quiet, never a primary. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PausedHint(onResume: () -> Unit, centered: Boolean) {
    val dimens = CarLinkTokens.dimens
    Column {
        Spacer(Modifier.height(dimens.targetGap))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.align(Alignment.CenterVertically).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                CarIcon(R.drawable.carlink_ic_pause_circle, null, size = dimens.iconTertiary,
                    tint = CarLinkTokens.colors.onSurfaceTertiary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.carlink_home_paused), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TertiaryButton(stringResource(R.string.carlink_home_resume), onResume,
                Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(dimens.buttonRadius)))
        }
    }
}

// ---- Per-state copy and actions ---------------------------------------------------------

private class HeroAction(val label: String, @param:DrawableRes val icon: Int?, val onClick: (HomeActions) -> () -> Unit)
private class HeroHint(val text: String, @param:DrawableRes val icon: Int)
private class HeroContent(
    val title: String,
    val subtitle: String,
    val primary: HeroAction? = null,
    val tonal: HeroAction? = null,
    val text: HeroAction? = null,
    val hint: HeroHint? = null,
)

@Composable
private fun heroContent(model: HeroModel, compact: Boolean): HeroContent {
    val engine = model.engine?.let { engineName(it) }
    val phone = model.phoneName?.let(::isolate)
        ?: if (model.engine != null) isolate(phoneKindDescription(model.phoneKind)) else stringResource(R.string.carlink_home_your_phone)
    return when (model.kind) {
        HeroKind.READY -> {
            val target = model.defaultPhone
            HeroContent(
                title = stringResource(R.string.carlink_home_ready_title),
                subtitle = if (target?.connected == true) stringResource(R.string.carlink_home_ready_subtitle_nearby, phone)
                else stringResource(R.string.carlink_home_ready_subtitle),
                primary = HeroAction(
                    if (compact) stringResource(R.string.carlink_home_connect_short)
                    else stringResource(R.string.carlink_home_connect_phone, phone),
                    R.drawable.carlink_ic_link,
                ) { a -> { target?.let(a.connect) } },
            )
        }
        HeroKind.WAITING -> HeroContent(
            title = stringResource(R.string.carlink_home_waiting_title),
            subtitle = stringResource(
                if (model.hasUnknownPhones) R.string.carlink_home_waiting_subtitle_unknown else R.string.carlink_home_waiting_subtitle),
            tonal = HeroAction(stringResource(R.string.carlink_home_open_bluetooth_settings), R.drawable.carlink_ic_bluetooth) { a ->
                a.openBluetoothSettings
            },
            text = HeroAction(stringResource(R.string.carlink_home_look_again), R.drawable.carlink_ic_refresh) { a -> a.refreshPhones },
        )
        HeroKind.BLUETOOTH_OFF -> HeroContent(
            title = stringResource(R.string.carlink_home_bt_off_title),
            subtitle = stringResource(R.string.carlink_home_bt_off_subtitle),
            primary = HeroAction(stringResource(R.string.carlink_home_turn_on_bluetooth), R.drawable.carlink_ic_bluetooth) { a ->
                a.openBluetoothSettings
            },
            hint = HeroHint(stringResource(R.string.carlink_home_cable_hint), R.drawable.carlink_ic_usb),
        )
        HeroKind.NO_PERMISSION -> HeroContent(
            title = stringResource(R.string.carlink_home_no_permission_title),
            subtitle = stringResource(R.string.carlink_home_no_permission_subtitle),
            primary = HeroAction(stringResource(R.string.carlink_home_allow_access), R.drawable.carlink_ic_lock) { a ->
                { a.navigate(Route.ONBOARDING) }
            },
            text = HeroAction(stringResource(R.string.carlink_home_open_app_settings), R.drawable.carlink_ic_open_in_new) { a ->
                a.openAppSettings
            },
        )
        HeroKind.NO_BLUETOOTH -> HeroContent(
            title = stringResource(R.string.carlink_home_no_bluetooth_title),
            subtitle = stringResource(R.string.carlink_home_no_bluetooth_subtitle),
        )
        HeroKind.CONNECTING -> HeroContent(
            title = stringResource(R.string.carlink_home_connecting_title, phone),
            subtitle = stringResource(R.string.carlink_home_connecting_subtitle, engine ?: phone),
            primary = HeroAction(stringResource(R.string.carlink_home_show_progress), R.drawable.carlink_ic_arrow_forward) { a ->
                { a.navigate(Route.CONNECTING) }
            },
            text = HeroAction(stringResource(R.string.carlink_home_cancel), R.drawable.carlink_ic_close) { a -> a.disconnect },
        )
        HeroKind.LIVE -> HeroContent(
            title = stringResource(R.string.carlink_home_live_title),
            subtitle = "",
            primary = HeroAction(stringResource(R.string.carlink_home_return_to, engine.orEmpty()), R.drawable.carlink_ic_play_arrow_filled) { a ->
                a.returnToProjection
            },
            text = HeroAction(stringResource(R.string.carlink_home_disconnect), R.drawable.carlink_ic_link_off) { a -> a.disconnect },
        )
        HeroKind.FAILED -> HeroContent(
            title = engine?.let { stringResource(R.string.carlink_home_failed_title_engine, it) }
                ?: stringResource(R.string.carlink_home_failed_title),
            // The engine's detail may be untranslated; isolate it so RTL punctuation stays put.
            subtitle = model.detail?.takeIf { it.isNotBlank() }?.let(::isolate)
                ?: stringResource(R.string.carlink_home_failed_body_fallback),
            primary = HeroAction(stringResource(R.string.carlink_home_try_again), R.drawable.carlink_ic_refresh) { a -> a.retry },
            text = HeroAction(stringResource(R.string.carlink_home_diagnostics), R.drawable.carlink_ic_monitor_heart) { a ->
                { a.navigate(Route.DIAGNOSTICS) }
            },
        )
    }
}
