package com.carlink.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.core.LinkStage
import com.carlink.app.ui.about.AboutScreen
import com.carlink.app.ui.connecting.ConnectingScreen
import com.carlink.app.ui.diagnostics.DiagnosticsScreen
import com.carlink.app.ui.home.HomeScreen
import com.carlink.app.ui.onboarding.OnboardingScreen
import com.carlink.app.ui.settings.SettingsScreen
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion

/** CarLink's screens (DESIGN_SPEC §4.1). Home is the hub; everything is one level below it. */
enum class Route { HOME, CONNECTING, SETTINGS, ONBOARDING, DIAGNOSTICS, ABOUT }

@Composable
fun CarLinkApp(vm: CarLinkViewModel, onExitToCarHome: () -> Unit) {
    // Back stack of routes; Home is always at the bottom once onboarding is done.
    var stack by rememberSaveable { mutableStateOf(listOf(if (vm.onboardingDone) Route.HOME else Route.ONBOARDING)) }
    val route = stack.last()
    val navigate: (Route) -> Unit = { target ->
        stack = when {
            target == Route.HOME -> listOf(Route.HOME)
            // Connecting replaces itself; one attempt screen at a time.
            target == route -> stack
            else -> stack + target
        }
    }
    val back: () -> Unit = { stack = if (stack.size > 1) stack.dropLast(1) else listOf(Route.HOME) }
    val link by vm.link.collectAsStateWithLifecycle()

    // An attempt (from a tap, a USB plug-in or a phone's Bluetooth) shows the stage timeline;
    // Connecting itself returns Home when the attempt ends.
    LaunchedEffect(link.stage) {
        if ((link.stage == LinkStage.WAITING || link.stage == LinkStage.CONNECTING) &&
            (route == Route.HOME || route == Route.ONBOARDING && vm.onboardingDone)) {
            navigate(Route.CONNECTING)
        }
    }

    val goHome = { navigate(Route.HOME) }
    BackHandler(enabled = route != Route.HOME && route != Route.ONBOARDING) {
        if (route == Route.CONNECTING) vm.disconnect()
        back()
    }
    BackHandler(enabled = route == Route.HOME) { onExitToCarHome() }

    val reduceMotion = LocalReduceMotion.current
    val travel = with(LocalDensity.current) { 48.dp.roundToPx() }
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val duration = if (reduceMotion) 0 else CarMotion.LONG
                // Shared-axis X: deeper screens enter from the end, going back returns from the start.
                val forward = targetState.ordinal > initialState.ordinal || initialState == Route.HOME
                val direction = if (forward) 1 else -1
                (slideInHorizontally(tween(duration, easing = CarMotion.EmphasizedDecelerate)) { direction * travel } +
                    fadeIn(tween(duration))) togetherWith
                    (slideOutHorizontally(tween(duration, easing = CarMotion.EmphasizedAccelerate)) { -direction * travel } +
                        fadeOut(tween(duration / 2)))
            },
            label = "route",
        ) { current ->
            when (current) {
                Route.HOME -> HomeScreen(vm, navigate = navigate)
                Route.CONNECTING -> ConnectingScreen(vm, onBack = back, navigate = navigate)
                Route.SETTINGS -> SettingsScreen(vm, onBack = back, navigate = navigate)
                Route.ONBOARDING -> OnboardingScreen(vm, onDone = { vm.completeOnboarding(); goHome() })
                Route.DIAGNOSTICS -> DiagnosticsScreen(vm, onBack = back)
                Route.ABOUT -> AboutScreen(vm, onBack = back)
            }
        }
    }
}
