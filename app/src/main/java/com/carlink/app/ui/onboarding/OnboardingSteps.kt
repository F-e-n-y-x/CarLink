package com.carlink.app.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.components.CarIcon
import com.carlink.app.ui.components.EngineBadge
import com.carlink.app.ui.components.OnboardingAdaptiveRow
import com.carlink.app.ui.components.OnboardingAvatar
import com.carlink.app.ui.components.OnboardingCheckRow
import com.carlink.app.ui.components.OnboardingChoiceCard
import com.carlink.app.ui.components.OnboardingNumberedSteps
import com.carlink.app.ui.components.OnboardingPermissionRow
import com.carlink.app.ui.components.OnboardingStatusLabel
import com.carlink.app.ui.components.PrimaryButton
import com.carlink.app.ui.components.RowDivider
import com.carlink.app.ui.components.SecondaryButton
import com.carlink.app.ui.components.SectionCard
import com.carlink.app.ui.components.SectionHeader
import com.carlink.app.ui.components.SettingRow
import com.carlink.app.ui.components.TertiaryButton
import com.carlink.app.ui.components.ToggleRow
import com.carlink.app.ui.components.engineName
import com.carlink.app.ui.components.phoneIcon
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.HeightClass
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.UiProfile

/*
 * The five wizard steps (DESIGN_SPEC §6.4). They are stateless: OnboardingScreen feeds them
 * from the ViewModel, the permission checklist and the device report, and previews feed them
 * samples.
 */

/** User data (phone names) in a Unicode isolate, so it never reorders RTL text around it. */
// Explicit isolates: BidiFormatter skips wrapping when its locale context matches the text.
internal fun isolate(text: String): String = "\u2068$text\u2069" // FSI … PDI

/** Technical values (versions, pixel sizes) always read left to right (DESIGN_SPEC §7.3). */
private fun ltr(text: String): String =
    "\u2066$text\u2069" // LRI … PDI

/** A step's title block; the title also names the pane for accessibility services. */
@Composable
internal fun StepPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    hero: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = CarLinkTokens.layout
    val dimens = CarLinkTokens.dimens
    val titleStyle = when {
        layout.height == HeightClass.COMPACT -> MaterialTheme.typography.headlineMedium
        !layout.isTwoPane -> MaterialTheme.typography.headlineLarge
        else -> MaterialTheme.typography.displaySmall
    }
    Column(modifier.fillMaxWidth().widthIn(max = dimens.contentMaxWidth).semantics { paneTitle = title }) {
        if (hero != null) {
            hero()
            Spacer(Modifier.height(dimens.gutter))
        }
        Text(title, style = titleStyle, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(dimens.gutter))
        content()
    }
}

/** A quiet way past a gated step, with the consequence stated first. */
@Composable
private fun SkipNote(text: String, onSkip: () -> Unit) {
    OnboardingAdaptiveRow(
        Modifier.padding(top = CarLinkTokens.dimens.targetGap),
        leading = { CarIcon(R.drawable.carlink_ic_info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = { TertiaryButton(stringResource(R.string.carlink_onboarding_skip_for_now), onSkip) },
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- 1. Welcome & safety --------------------------------------------------------------

@Composable
internal fun WelcomeStep() {
    val dimens = CarLinkTokens.dimens
    StepPage(
        title = stringResource(R.string.carlink_onboarding_welcome_title),
        subtitle = stringResource(R.string.carlink_onboarding_welcome_body),
    ) {
        SectionCard {
            EngineMapRow(PhoneKind.IPHONE, stringResource(R.string.carlink_onboarding_phone_iphone), Engine.CARPLAY)
            RowDivider()
            EngineMapRow(PhoneKind.ANDROID, stringResource(R.string.carlink_onboarding_phone_android), Engine.ANDROID_AUTO)
        }
        Spacer(Modifier.height(dimens.gutter))
        // The safety note is inline; Start acknowledges it (no extra dialog).
        val colors = CarLinkTokens.colors
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = colors.warningContainer,
            contentColor = colors.onWarningContainer,
        ) {
            Row(Modifier.padding(dimens.gutter).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                CarIcon(R.drawable.carlink_ic_local_parking, null, size = dimens.iconPrimary, tint = colors.onWarningContainer)
                Spacer(Modifier.width(dimens.targetGap))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.carlink_onboarding_safety_title),
                        style = MaterialTheme.typography.titleMedium, color = colors.onWarningContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.carlink_onboarding_safety_body),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onWarningContainer)
                }
            }
        }
    }
}

/** "iPhone → CarPlay": what CarLink starts for each kind of phone. */
@Composable
private fun EngineMapRow(kind: PhoneKind, phone: String, engine: Engine) {
    val description = stringResource(R.string.carlink_onboarding_engine_map_cd, phone, engineName(engine))
    OnboardingAdaptiveRow(
        Modifier.clearAndSetSemantics { contentDescription = description },
        leading = { OnboardingAvatar(phoneIcon(kind)) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarIcon(R.drawable.carlink_ic_arrow_forward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(CarLinkTokens.dimens.targetGap))
                EngineBadge(engine)
            }
        },
    ) {
        Text(phone, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- 2. Your device ---------------------------------------------------------------------

@Composable
internal fun DeviceStep(
    profile: UiProfile,
    report: DeviceReport,
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    StepPage(
        title = stringResource(R.string.carlink_onboarding_device_title),
        subtitle = stringResource(R.string.carlink_onboarding_device_body),
    ) {
        SectionCard {
            SettingRow(
                title = stringResource(
                    if (profile == UiProfile.CAR) R.string.carlink_onboarding_device_car else R.string.carlink_onboarding_device_handheld,
                ),
                supporting = stringResource(
                    if (profile == UiProfile.CAR) R.string.carlink_onboarding_device_car_body
                    else R.string.carlink_onboarding_device_handheld_body,
                ),
                icon = when {
                    profile == UiProfile.CAR -> R.drawable.carlink_ic_directions_car
                    tablet -> R.drawable.carlink_ic_tablet_android
                    else -> R.drawable.carlink_ic_smartphone
                },
            )
            RowDivider()
            SettingRow(
                title = stringResource(R.string.carlink_onboarding_device_android),
                supporting = ltr(stringResource(R.string.carlink_onboarding_device_android_value, report.androidRelease, report.apiLevel)),
                icon = R.drawable.carlink_ic_android,
            )
            RowDivider()
            SettingRow(
                title = stringResource(R.string.carlink_onboarding_device_screen),
                supporting = ltr(stringResource(R.string.carlink_onboarding_device_screen_value, report.screenWidthPx, report.screenHeightPx)),
                icon = R.drawable.carlink_ic_aspect_ratio,
            )
        }

        SectionHeader(stringResource(R.string.carlink_onboarding_checks_title))
        OnboardingStatusLabel(
            stringResource(if (report.allGood) R.string.carlink_onboarding_checks_good else R.string.carlink_onboarding_checks_limited),
            ok = report.allGood, live = true,
        )
        Spacer(Modifier.height(dimens.targetGap))
        SectionCard {
            OnboardingCheckRow(
                title = stringResource(R.string.carlink_onboarding_check_bluetooth),
                result = stringResource(
                    when (report.bluetooth) {
                        BluetoothCheck.ON -> R.string.carlink_onboarding_check_bluetooth_on
                        BluetoothCheck.OFF -> R.string.carlink_onboarding_check_bluetooth_off
                        BluetoothCheck.UNSUPPORTED -> R.string.carlink_onboarding_check_bluetooth_unsupported
                    },
                ),
                ok = report.bluetooth == BluetoothCheck.ON,
                icon = if (report.bluetooth == BluetoothCheck.ON) R.drawable.carlink_ic_bluetooth else R.drawable.carlink_ic_bluetooth_disabled,
                action = if (report.bluetooth == BluetoothCheck.OFF) {
                    {
                        SecondaryButton(stringResource(R.string.carlink_onboarding_check_bluetooth_turn_on), onOpenBluetoothSettings,
                            icon = R.drawable.carlink_ic_open_in_new)
                    }
                } else null,
            )
            RowDivider()
            OnboardingCheckRow(
                title = stringResource(R.string.carlink_onboarding_check_usb),
                result = stringResource(if (report.usbHost) R.string.carlink_onboarding_check_usb_ok else R.string.carlink_onboarding_check_usb_missing),
                ok = report.usbHost,
                icon = if (report.usbHost) R.drawable.carlink_ic_usb else R.drawable.carlink_ic_usb_off,
            )
            RowDivider()
            OnboardingCheckRow(
                title = stringResource(R.string.carlink_onboarding_check_wifi_direct),
                result = stringResource(
                    if (report.wifiDirect) R.string.carlink_onboarding_check_wifi_direct_ok else R.string.carlink_onboarding_check_wifi_direct_missing,
                ),
                ok = report.wifiDirect,
                icon = if (report.wifiDirect) R.drawable.carlink_ic_p2p else R.drawable.carlink_ic_wifi_off,
            )
        }
        Spacer(Modifier.height(dimens.gutter))
        SectionCard {
            ToggleRow(
                title = stringResource(R.string.carlink_onboarding_start_on_boot),
                supporting = stringResource(R.string.carlink_onboarding_start_on_boot_body),
                checked = startOnBoot,
                onCheckedChange = onStartOnBootChange,
                icon = R.drawable.carlink_ic_power_settings_new,
            )
        }
    }
}

// ---- 3. Permissions -----------------------------------------------------------------------

@Composable
internal fun PermissionsStep(
    items: List<PermissionItem>,
    requiredGranted: Boolean,
    onAllow: (SetupPermission) -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
) {
    StepPage(
        title = stringResource(R.string.carlink_onboarding_permissions_title),
        subtitle = stringResource(R.string.carlink_onboarding_permissions_body),
    ) {
        SectionCard {
            items.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                OnboardingPermissionRow(
                    icon = item.permission.icon,
                    title = stringResource(item.permission.title),
                    need = stringResource(item.permission.need.label),
                    why = stringResource(item.permission.why),
                    status = item.status,
                    onAllow = { onAllow(item.permission) },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
        if (requiredGranted) {
            Spacer(Modifier.height(CarLinkTokens.dimens.targetGap))
            OnboardingStatusLabel(stringResource(R.string.carlink_onboarding_permissions_ready), ok = true, live = true)
        } else {
            SkipNote(stringResource(R.string.carlink_onboarding_permissions_skip_note), onSkip)
        }
    }
}

// ---- 4. Connect your phone ----------------------------------------------------------------

@Composable
internal fun ConnectStep(
    kind: PhoneKind?,
    onKindChange: (PhoneKind) -> Unit,
    phones: List<PairedPhone>,
    bluetooth: BluetoothAvailability,
    link: LinkState,
    onConnect: (PairedPhone) -> Unit,
    onRetry: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onAllowAccess: () -> Unit,
    onSkip: () -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val compactHeight = CarLinkTokens.layout.height == HeightClass.COMPACT
    StepPage(
        title = stringResource(R.string.carlink_onboarding_connect_title),
        subtitle = stringResource(R.string.carlink_onboarding_connect_body),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= dimens.rowTwoLine * 5
            val cards: @Composable (Modifier, Boolean) -> Unit = { cardModifier, horizontal ->
                OnboardingChoiceCard(
                    phoneIcon(PhoneKind.IPHONE), stringResource(R.string.carlink_onboarding_phone_iphone),
                    stringResource(R.string.carlink_onboarding_phone_iphone_body), kind == PhoneKind.IPHONE,
                    onClick = { onKindChange(PhoneKind.IPHONE) }, modifier = cardModifier, horizontal = horizontal,
                )
                OnboardingChoiceCard(
                    phoneIcon(PhoneKind.ANDROID), stringResource(R.string.carlink_onboarding_phone_android),
                    stringResource(R.string.carlink_onboarding_phone_android_body), kind == PhoneKind.ANDROID,
                    onClick = { onKindChange(PhoneKind.ANDROID) }, modifier = cardModifier, horizontal = horizontal,
                )
            }
            if (sideBySide) {
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(dimens.gutter)) {
                    cards(Modifier.weight(1f), compactHeight)
                }
            } else {
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
                    cards(Modifier.fillMaxWidth(), true)
                }
            }
        }

        if (kind != null && kind != PhoneKind.UNKNOWN) {
            SectionHeader(stringResource(
                if (kind == PhoneKind.IPHONE) R.string.carlink_onboarding_connect_on_iphone else R.string.carlink_onboarding_connect_on_android,
            ))
            OnboardingNumberedSteps(
                if (kind == PhoneKind.IPHONE) {
                    listOf(
                        stringResource(R.string.carlink_onboarding_iphone_step1),
                        stringResource(R.string.carlink_onboarding_iphone_step2),
                        stringResource(R.string.carlink_onboarding_iphone_step3),
                    )
                } else {
                    listOf(
                        stringResource(R.string.carlink_onboarding_android_step1),
                        stringResource(R.string.carlink_onboarding_android_step2),
                        stringResource(R.string.carlink_onboarding_android_step3),
                    )
                },
            )
            Spacer(Modifier.height(dimens.gutter))
            ConnectArea(kind, phones, bluetooth, link, onConnect, onRetry, onOpenBluetoothSettings, onAllowAccess)
        } else if (link.stage != LinkStage.IDLE) {
            Spacer(Modifier.height(dimens.gutter))
            LinkStatusCard(link, onRetry)
        }

        if (link.stage != LinkStage.CONNECTED) {
            SkipNote(stringResource(R.string.carlink_onboarding_connect_skip_note), onSkip)
        }
    }
}

@Composable
private fun ConnectArea(
    kind: PhoneKind,
    phones: List<PairedPhone>,
    bluetooth: BluetoothAvailability,
    link: LinkState,
    onConnect: (PairedPhone) -> Unit,
    onRetry: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onAllowAccess: () -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    if (link.stage != LinkStage.IDLE) {
        LinkStatusCard(link, onRetry)
        if (link.stage != LinkStage.FAILED) return
        Spacer(Modifier.height(dimens.gutter))
    }
    val matching = phones.filter { it.kind == kind }.sortedByDescending { it.connected }.take(MAX_PHONES)
    val kindName = stringResource(
        if (kind == PhoneKind.IPHONE) R.string.carlink_onboarding_phone_iphone else R.string.carlink_onboarding_phone_android,
    )
    when {
        bluetooth == BluetoothAvailability.NO_PERMISSION -> HintWithAction(
            stringResource(R.string.carlink_onboarding_connect_need_permission),
        ) { SecondaryButton(stringResource(R.string.carlink_onboarding_connect_allow_access), onAllowAccess) }

        bluetooth == BluetoothAvailability.UNSUPPORTED -> HintWithAction(
            stringResource(R.string.carlink_onboarding_connect_usb_only),
        ) {}

        matching.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(dimens.targetGap)) {
            matching.forEachIndexed { index, phone ->
                val label = stringResource(R.string.carlink_onboarding_connect_phone, isolate(phone.name))
                // After a failure, Try again is the recovery; the phone buttons step back to secondary.
                if (index == 0 && link.stage != LinkStage.FAILED) {
                    PrimaryButton(label, { onConnect(phone) }, Modifier.fillMaxWidth(), icon = R.drawable.carlink_ic_link)
                } else {
                    SecondaryButton(label, { onConnect(phone) }, Modifier.fillMaxWidth(), icon = R.drawable.carlink_ic_link)
                }
            }
            Text(stringResource(R.string.carlink_onboarding_connect_usb_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        else -> HintWithAction(
            stringResource(
                if (bluetooth == BluetoothAvailability.OFF) R.string.carlink_onboarding_connect_bluetooth_off
                else R.string.carlink_onboarding_connect_none_paired,
                kindName,
            ),
        ) {
            SecondaryButton(stringResource(R.string.carlink_onboarding_connect_open_bluetooth), onOpenBluetoothSettings,
                icon = R.drawable.carlink_ic_bluetooth)
        }
    }
}

private const val MAX_PHONES = 3

@Composable
private fun HintWithAction(text: String, action: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(CarLinkTokens.dimens.targetGap)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

/** Compact inline status of the real connection attempt (stage text and progress). */
@Composable
private fun LinkStatusCard(link: LinkState, onRetry: () -> Unit) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    val reduceMotion = LocalReduceMotion.current
    val phone = link.phoneName?.let(::isolate)
    val engine = link.engine?.let { engineName(it) }
    val title = when (link.stage) {
        LinkStage.WAITING -> phone?.let { stringResource(R.string.carlink_onboarding_link_waiting_for, it) }
            ?: stringResource(R.string.carlink_onboarding_link_waiting)
        LinkStage.CONNECTING -> engine?.let { stringResource(R.string.carlink_onboarding_link_starting, it) }
            ?: stringResource(R.string.carlink_onboarding_link_connecting)
        LinkStage.CONNECTED -> phone?.let { stringResource(R.string.carlink_onboarding_link_connected_phone, it) }
            ?: stringResource(R.string.carlink_onboarding_link_connected)
        LinkStage.FAILED -> stringResource(R.string.carlink_onboarding_link_failed)
        LinkStage.IDLE -> return
    }
    SectionCard {
        OnboardingAdaptiveRow(
            leading = {
                val size = dimens.iconSecondary
                when (link.stage) {
                    LinkStage.CONNECTED -> CarIcon(R.drawable.carlink_ic_check_circle_filled, null, size = size, tint = colors.success)
                    LinkStage.FAILED -> CarIcon(R.drawable.carlink_ic_error_filled, null, size = size, tint = MaterialTheme.colorScheme.error)
                    // The one looping animation (DESIGN_SPEC §5.7); static when motion is reduced.
                    else -> if (reduceMotion) CarIcon(R.drawable.carlink_ic_sync, null, size = size, tint = MaterialTheme.colorScheme.primary)
                    else CircularProgressIndicator(Modifier.size(size), strokeWidth = 4.dp)
                }
            },
            trailing = if (link.stage == LinkStage.FAILED) {
                { SecondaryButton(stringResource(R.string.carlink_onboarding_link_retry), onRetry, icon = R.drawable.carlink_ic_refresh) }
            } else null,
        ) {
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = when (link.stage) {
                        LinkStage.CONNECTED -> colors.success
                        LinkStage.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                val detail = link.detail?.takeIf { it.isNotBlank() }
                if (detail != null && link.stage != LinkStage.CONNECTED) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (link.stage == LinkStage.CONNECTING && link.stepCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (link.step.toFloat() / link.stepCount).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                if (link.stage == LinkStage.CONNECTED && link.engine != null) {
                    Spacer(Modifier.height(8.dp))
                    EngineBadge(link.engine)
                }
            }
        }
    }
}

// ---- 5. Done ------------------------------------------------------------------------------

@Composable
internal fun DoneStep(
    phoneName: String?,
    phoneConnected: Boolean,
    permissionsAll: Boolean,
    permissionsRequired: Boolean,
    @DrawableRes phoneGlyph: Int,
) {
    val dimens = CarLinkTokens.dimens
    val colors = CarLinkTokens.colors
    StepPage(
        title = stringResource(R.string.carlink_onboarding_done_title),
        subtitle = phoneName?.let { stringResource(R.string.carlink_onboarding_done_body_phone, isolate(it)) }
            ?: stringResource(R.string.carlink_onboarding_done_body),
        hero = {
            OnboardingAvatar(
                R.drawable.carlink_ic_check_circle_filled, size = dimens.avatarHero,
                container = MaterialTheme.colorScheme.surfaceContainerHigh, tint = colors.success,
            )
        },
    ) {
        SectionCard {
            OnboardingCheckRow(
                title = stringResource(R.string.carlink_onboarding_done_phone),
                result = if (phoneConnected && phoneName != null) {
                    stringResource(R.string.carlink_onboarding_done_phone_connected, isolate(phoneName))
                } else {
                    stringResource(R.string.carlink_onboarding_done_phone_later)
                },
                ok = phoneConnected,
                icon = phoneGlyph,
            )
            RowDivider()
            OnboardingCheckRow(
                title = stringResource(R.string.carlink_onboarding_done_permissions),
                result = stringResource(
                    when {
                        permissionsAll -> R.string.carlink_onboarding_done_permissions_ok
                        permissionsRequired -> R.string.carlink_onboarding_done_permissions_recommended
                        else -> R.string.carlink_onboarding_done_permissions_missing
                    },
                ),
                ok = permissionsAll,
                icon = R.drawable.carlink_ic_lock,
            )
        }
    }
}
