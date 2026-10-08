package com.carlink.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.R
import com.carlink.app.core.Engine
import com.carlink.app.core.LinkStage
import com.carlink.app.core.LinkState
import com.carlink.app.core.PhoneKind
import com.carlink.app.core.Transport
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.detect.PairedPhone
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.app.ui.components.CarTopBar
import com.carlink.app.ui.components.OnboardingProgress
import com.carlink.app.ui.components.OnboardingStepList
import com.carlink.app.ui.components.PermissionRowStatus
import com.carlink.app.ui.components.PrimaryButton
import com.carlink.app.ui.components.TertiaryButton
import com.carlink.app.ui.components.phoneIcon
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.CarLinkTokens
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.HeightClass
import com.carlink.app.ui.theme.LocalReduceMotion
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile

private const val STEP_WELCOME = 0
private const val STEP_DEVICE = 1
private const val STEP_PERMISSIONS = 2
private const val STEP_CONNECT = 3
private const val STEP_DONE = 4
private const val STEP_COUNT = 5

/**
 * The setup wizard (DESIGN_SPEC §6.4): welcome and safety, this device, permissions, the
 * phone-side steps, done. Shown on first launch and from Settings > "Run setup again";
 * [onDone] leaves it (Skip setup or Go to Home).
 */
@Composable
fun OnboardingScreen(vm: CarLinkViewModel, onDone: () -> Unit) {
    val profile = CarLinkTokens.profile
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    var furthest by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    var bootDefaultApplied by rememberSaveable { mutableStateOf(false) }
    var chosenKind by rememberSaveable { mutableStateOf<PhoneKind?>(null) }
    // First run (not "Run setup again"): only then may setup choose defaults for the driver.
    val firstRun = rememberSaveable { !vm.onboardingDone }

    val home by vm.home.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val permissions = rememberSetupPermissions()
    val device by rememberDeviceReport()

    fun goTo(target: Int) {
        step = target.coerceIn(STEP_WELCOME, STEP_DONE)
        if (step > furthest) furthest = step
    }

    // "Open CarLink when the car starts" defaults on for car displays only, once per setup.
    LaunchedEffect(step) {
        if (step == STEP_DEVICE && !bootDefaultApplied) {
            bootDefaultApplied = true
            if (firstRun && profile == UiProfile.CAR && !connection.startOnBoot) vm.setStartOnBoot(true)
        }
    }

    BackHandler(enabled = step > STEP_WELCOME) { goTo(step - 1) }

    val connected = link.stage == LinkStage.CONNECTED
    val kind = chosenKind ?: home.defaultPhone?.kind?.takeIf { it != PhoneKind.UNKNOWN }
    val primary = when (step) {
        STEP_WELCOME -> PrimaryAction(stringResource(R.string.carlink_onboarding_start), enabled = true,
            icon = R.drawable.carlink_ic_arrow_forward) { goTo(STEP_DEVICE) }
        STEP_PERMISSIONS -> PrimaryAction(stringResource(R.string.carlink_onboarding_continue),
            enabled = permissions.requiredGranted) { goTo(STEP_CONNECT) }
        STEP_CONNECT -> PrimaryAction(stringResource(R.string.carlink_onboarding_continue), enabled = connected) {
            goTo(STEP_DONE)
        }
        STEP_DONE -> PrimaryAction(stringResource(R.string.carlink_onboarding_go_home), enabled = true,
            icon = R.drawable.carlink_ic_home, onClick = onDone)
        else -> PrimaryAction(stringResource(R.string.carlink_onboarding_continue), enabled = true) { goTo(step + 1) }
    }

    OnboardingScaffold(
        step = step,
        furthest = furthest,
        onSelectStep = ::goTo,
        onSkipSetup = if (step < STEP_DONE) onDone else null,
        onBack = if (step > STEP_WELCOME) ({ goTo(step - 1) }) else null,
        primary = primary,
    ) { shown ->
        when (shown) {
            STEP_WELCOME -> WelcomeStep()
            STEP_DEVICE -> DeviceStep(profile, device, connection.startOnBoot, vm::setStartOnBoot, vm::openBluetoothSettings)
            STEP_PERMISSIONS -> PermissionsStep(
                permissions.items, permissions.requiredGranted,
                onAllow = permissions::request, onOpenSettings = vm::openAppSettings, onSkip = { goTo(STEP_CONNECT) },
            )
            STEP_CONNECT -> ConnectStep(
                kind = kind, onKindChange = { chosenKind = it },
                phones = home.phones, bluetooth = home.bluetooth, link = link,
                onConnect = vm::connect, onRetry = vm::retry,
                onOpenBluetoothSettings = vm::openBluetoothSettings,
                onAllowAccess = { goTo(STEP_PERMISSIONS) },
                onSkip = { goTo(STEP_DONE) },
            )
            else -> {
                val phone = (if (connected) link.phoneName else null) ?: home.defaultPhone?.name
                DoneStep(
                    phoneName = phone, phoneConnected = connected,
                    permissionsAll = permissions.allGranted, permissionsRequired = permissions.requiredGranted,
                    phoneGlyph = phoneIcon(home.defaultPhone?.kind ?: kind ?: PhoneKind.UNKNOWN),
                )
            }
        }
    }
}

/** The step's one primary action. */
internal class PrimaryAction(val label: String, val enabled: Boolean, @param:DrawableRes val icon: Int? = null, val onClick: () -> Unit)

/**
 * Stateless frame of the wizard. Landscape EXPANDED+ windows get the numbered step list on
 * the start side and the action bottom-end; everything else (phones, portrait, the DiLink ⅓
 * split, short landscape phones) gets a segmented progress bar and a sticky Back/Continue bar.
 */
@Composable
internal fun OnboardingScaffold(
    step: Int,
    furthest: Int,
    onSelectStep: (Int) -> Unit,
    onSkipSetup: (() -> Unit)?,
    onBack: (() -> Unit)?,
    primary: PrimaryAction,
    content: @Composable (step: Int) -> Unit,
) {
    val layout = CarLinkTokens.layout
    // A Surface (not just a background) so untinted text, like the top bar title, gets onBackground.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (layout.isTwoPane && layout.isLandscape) {
            TwoPaneWizard(step, furthest, onSelectStep, onSkipSetup, onBack, primary, content)
        } else {
            SinglePaneWizard(step, onSkipSetup, onBack, primary, content)
        }
    }
}

@Composable
private fun TwoPaneWizard(
    step: Int,
    furthest: Int,
    onSelectStep: (Int) -> Unit,
    onSkipSetup: (() -> Unit)?,
    onBack: (() -> Unit)?,
    primary: PrimaryAction,
    content: @Composable (step: Int) -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val listWidth = if (CarLinkTokens.profile == UiProfile.CAR) 360.dp else 280.dp
    Column(Modifier.fillMaxSize()) {
        CarTopBar(
            title = stringResource(R.string.carlink_onboarding_title),
            showBrand = true,
            actions = { if (onSkipSetup != null) TertiaryButton(stringResource(R.string.carlink_onboarding_skip_setup), onSkipSetup) },
        )
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.width(listWidth).fillMaxHeight()
                    .padding(start = dimens.screenMargin, bottom = dimens.screenMargin)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                OnboardingStepList(stepLabels(), step, furthest, onSelectStep)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                StepContent(step, Modifier.weight(1f), horizontalPadding = dimens.screenMargin + dimens.targetGap, content = content)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = dimens.screenMargin, vertical = dimens.targetGap),
                    horizontalArrangement = Arrangement.spacedBy(dimens.targetGap, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) TertiaryButton(stringResource(R.string.carlink_back), onBack)
                    PrimaryButton(primary.label, primary.onClick, enabled = primary.enabled, icon = primary.icon)
                }
            }
        }
    }
}

@Composable
private fun SinglePaneWizard(
    step: Int,
    onSkipSetup: (() -> Unit)?,
    onBack: (() -> Unit)?,
    primary: PrimaryAction,
    content: @Composable (step: Int) -> Unit,
) {
    val dimens = CarLinkTokens.dimens
    val layout = CarLinkTokens.layout
    val margin = if (layout.width == com.carlink.app.ui.theme.WidthClass.COMPACT) dimens.compactMargin else dimens.screenMargin
    val shortWindow = layout.height == HeightClass.COMPACT
    Column(Modifier.fillMaxSize()) {
        // Short windows (landscape phones) fold the title into the progress row to keep room for content.
        Row(
            Modifier.fillMaxWidth().heightIn(min = dimens.minTouch).padding(start = margin, end = margin - 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shortWindow) {
                OnboardingProgress(step, STEP_COUNT, Modifier.weight(1f))
            } else {
                Text(
                    stringResource(R.string.carlink_onboarding_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            if (onSkipSetup != null) {
                Spacer(Modifier.width(dimens.targetGap))
                TertiaryButton(stringResource(R.string.carlink_onboarding_skip_setup), onSkipSetup)
            }
        }
        if (!shortWindow) {
            OnboardingProgress(step, STEP_COUNT, Modifier.padding(horizontal = margin, vertical = 8.dp))
        }
        StepContent(step, Modifier.weight(1f), horizontalPadding = margin, centered = true, content = content)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = margin, vertical = if (shortWindow) 8.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(dimens.targetGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) TertiaryButton(stringResource(R.string.carlink_back), onBack)
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(primary.label, primary.onClick, Modifier.weight(1f, fill = false),
                        enabled = primary.enabled, icon = primary.icon)
                }
            }
        }
    }
}

/**
 * The scrolling step content, animated with a shared-axis X transition: forward steps enter
 * from the end side (mirrored in RTL), back steps from the start. Instant with reduce motion.
 */
@Composable
private fun StepContent(
    step: Int,
    modifier: Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    centered: Boolean = false,
    content: @Composable (step: Int) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travel = with(LocalDensity.current) { 48.dp.roundToPx() }
    val dimens = CarLinkTokens.dimens
    AnimatedContent(
        targetState = step,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val forward = targetState > initialState
                val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
                (slideInHorizontally(tween(CarMotion.LONG, easing = CarMotion.EmphasizedDecelerate)) { sign * travel } +
                    fadeIn(tween(CarMotion.MEDIUM, delayMillis = CarMotion.PRESS))) togetherWith
                    (slideOutHorizontally(tween(CarMotion.LONG, easing = CarMotion.EmphasizedAccelerate)) { -sign * travel } +
                        fadeOut(tween(CarMotion.PRESS)))
            }.using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.TopStart,
        label = "onboardingStep",
    ) { shown ->
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding)
                .padding(top = dimens.targetGap, bottom = dimens.gutter),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            content(shown)
        }
    }
}

@Composable
private fun stepLabels(): List<String> = listOf(
    stringResource(R.string.carlink_onboarding_step_welcome),
    stringResource(R.string.carlink_onboarding_step_device),
    stringResource(R.string.carlink_onboarding_step_permissions),
    stringResource(R.string.carlink_onboarding_step_connect),
    stringResource(R.string.carlink_onboarding_step_done),
)

// ---- Previews: car landscape 1440×810dp and phone portrait 400×860dp, one pair per step ----

private val samplePhones = listOf(
    PairedPhone("00:11:22:33:44:55", "Sara's iPhone", PhoneKind.IPHONE, connected = true),
    PairedPhone("66:77:88:99:AA:BB", "Pixel 9", PhoneKind.ANDROID, connected = false),
)

private val samplePermissions = listOf(
    PermissionItem(SetupPermission.NEARBY_DEVICES, PermissionRowStatus.GRANTED),
    PermissionItem(SetupPermission.LOCATION, PermissionRowStatus.ASK),
    PermissionItem(SetupPermission.NEARBY_WIFI, PermissionRowStatus.OPEN_SETTINGS),
    PermissionItem(SetupPermission.MICROPHONE, PermissionRowStatus.ASK),
    PermissionItem(SetupPermission.NOTIFICATIONS, PermissionRowStatus.GRANTED),
    PermissionItem(SetupPermission.OVERLAY, PermissionRowStatus.ASK),
    PermissionItem(SetupPermission.BATTERY, PermissionRowStatus.ASK),
)

@Composable
private fun PreviewStep(profile: UiProfile, step: Int) {
    CarLinkTheme(profile = profile, themeMode = ThemeMode.DARK) {
        val primary = when (step) {
            STEP_WELCOME -> PrimaryAction(stringResource(R.string.carlink_onboarding_start), true, R.drawable.carlink_ic_arrow_forward) {}
            STEP_PERMISSIONS, STEP_CONNECT -> PrimaryAction(stringResource(R.string.carlink_onboarding_continue), false) {}
            STEP_DONE -> PrimaryAction(stringResource(R.string.carlink_onboarding_go_home), true, R.drawable.carlink_ic_home) {}
            else -> PrimaryAction(stringResource(R.string.carlink_onboarding_continue), true) {}
        }
        OnboardingScaffold(
            step = step, furthest = step, onSelectStep = {},
            onSkipSetup = if (step < STEP_DONE) ({}) else null,
            onBack = if (step > STEP_WELCOME) ({}) else null,
            primary = primary,
        ) { shown ->
            when (shown) {
                STEP_WELCOME -> WelcomeStep()
                STEP_DEVICE -> DeviceStep(profile, DeviceReport.Sample, startOnBoot = profile == UiProfile.CAR, {}, {})
                STEP_PERMISSIONS -> PermissionsStep(samplePermissions, requiredGranted = false, {}, {}, {})
                STEP_CONNECT -> ConnectStep(
                    kind = PhoneKind.IPHONE, onKindChange = {}, phones = samplePhones, bluetooth = BluetoothAvailability.READY,
                    link = LinkState(LinkStage.CONNECTING, Engine.CARPLAY, Transport.WIRELESS, "Sara's iPhone", 2, 5, "Setting up Wi-Fi"),
                    onConnect = {}, onRetry = {}, onOpenBluetoothSettings = {}, onAllowAccess = {}, onSkip = {},
                )
                else -> DoneStep("Sara's iPhone", phoneConnected = true, permissionsAll = false, permissionsRequired = true, phoneGlyph = R.drawable.carlink_ic_phone_iphone)
            }
        }
    }
}

@Preview(name = "1 Welcome · car", widthDp = 1440, heightDp = 810)
@Composable private fun WelcomeCarPreview() = PreviewStep(UiProfile.CAR, STEP_WELCOME)

@Preview(name = "1 Welcome · phone", widthDp = 400, heightDp = 860)
@Composable private fun WelcomePhonePreview() = PreviewStep(UiProfile.HANDHELD, STEP_WELCOME)

@Preview(name = "2 Device · car", widthDp = 1440, heightDp = 810)
@Composable private fun DeviceCarPreview() = PreviewStep(UiProfile.CAR, STEP_DEVICE)

@Preview(name = "2 Device · phone", widthDp = 400, heightDp = 860)
@Composable private fun DevicePhonePreview() = PreviewStep(UiProfile.HANDHELD, STEP_DEVICE)

@Preview(name = "3 Permissions · car", widthDp = 1440, heightDp = 810)
@Composable private fun PermissionsCarPreview() = PreviewStep(UiProfile.CAR, STEP_PERMISSIONS)

@Preview(name = "3 Permissions · phone", widthDp = 400, heightDp = 860)
@Composable private fun PermissionsPhonePreview() = PreviewStep(UiProfile.HANDHELD, STEP_PERMISSIONS)

@Preview(name = "4 Connect · car", widthDp = 1440, heightDp = 810)
@Composable private fun ConnectCarPreview() = PreviewStep(UiProfile.CAR, STEP_CONNECT)

@Preview(name = "4 Connect · phone", widthDp = 400, heightDp = 860)
@Composable private fun ConnectPhonePreview() = PreviewStep(UiProfile.HANDHELD, STEP_CONNECT)

@Preview(name = "5 Done · car", widthDp = 1440, heightDp = 810)
@Composable private fun DoneCarPreview() = PreviewStep(UiProfile.CAR, STEP_DONE)

@Preview(name = "5 Done · phone", widthDp = 400, heightDp = 860)
@Composable private fun DonePhonePreview() = PreviewStep(UiProfile.HANDHELD, STEP_DONE)
