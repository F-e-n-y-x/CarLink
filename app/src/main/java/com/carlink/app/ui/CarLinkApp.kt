package com.carlink.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.carlink.app.ui.about.AboutScreen
import com.carlink.app.ui.diagnostics.DiagnosticsScreen
import com.carlink.app.ui.home.HomeScreen
import com.carlink.app.ui.settings.SettingsScreen
import com.carlink.app.ui.theme.CarMotion
import com.carlink.app.ui.theme.LocalReduceMotion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.core.core.LinkStage
import com.carlink.core.settings.SettingsCategory

/** CarLink's screens (DESIGN_SPEC §4.1). Home is the hub; everything is one level below it. */
enum class Route {
    HOME, SETTINGS, DIAGNOSTICS, ABOUT,
    /** First-time setup from its welcome (first launch, Settings › General › Set up again). */
    SETUP,
    /** The setup's pairing step (Home's "Pair a phone"). */
    SETUP_PAIR,
}

/** Back on Settings that the car tile opened goes back to the phone's picture while it is live. */
internal fun backReturnsToProjection(route: Route, fromCar: Boolean, live: Boolean) =
    route == Route.SETTINGS && fromCar && live

@Composable
fun CarLinkApp(vm: CarLinkViewModel, onExitToCarHome: () -> Unit) {
    // Back stack of routes; Home is always at the bottom. The first-time setup runs over Home at
    // the first launch (and when the driver asks); Home still asks for permissions inline.
    var stack by rememberSaveable { mutableStateOf(if (vm.setupNeeded()) listOf(Route.HOME, Route.SETUP) else listOf(Route.HOME)) }
    // The Settings category another screen asked to open (Diagnostics), or null for the default.
    var settingsStart by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val route = stack.last()
    val navigate: (Route) -> Unit = { target ->
        if (target == Route.SETTINGS) settingsStart = null
        stack = when {
            target == Route.HOME -> listOf(Route.HOME)
            // Connecting replaces itself; one attempt screen at a time.
            target == route -> stack
            else -> stack + target
        }
    }
    // The car tile in CarPlay / Android Auto (HomeActivity) opens Settings over the live session.
    val fromCar by vm.settingsFromCar.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    LaunchedEffect(fromCar) { if (fromCar && route != Route.SETTINGS) navigate(Route.SETTINGS) }
    val back: () -> Unit = {
        if (backReturnsToProjection(route, fromCar, link.stage == LinkStage.CONNECTED)) vm.returnToProjection()
        if (route == Route.SETTINGS) vm.settingsFromCar.value = false
        stack = if (stack.size > 1) stack.dropLast(1) else listOf(Route.HOME)
    }

    val openSettings: (SettingsCategory) -> Unit = { category -> navigate(Route.SETTINGS); settingsStart = category }
    BackHandler(enabled = route != Route.HOME) { back() }
    BackHandler(enabled = route == Route.HOME) { onExitToCarHome() }

    val reduceMotion = LocalReduceMotion.current
    val travel = with(LocalDensity.current) { 48.dp.roundToPx() }
    // A Surface (not a plain background) so every screen's text defaults to onBackground.
    androidx.compose.material3.Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        // Every screen draws its atmosphere behind the system bars and pads its own content.
        Box(Modifier.fillMaxSize()) {
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
                    Route.SETTINGS -> SettingsScreen(vm, onBack = back, navigate = navigate, startCategory = settingsStart)
                    Route.DIAGNOSTICS -> DiagnosticsScreen(vm, onBack = back, openSettings = openSettings)
                    Route.ABOUT -> AboutScreen(onBack = back)
                    Route.SETUP, Route.SETUP_PAIR -> com.carlink.app.ui.setup.SetupScreen(vm,
                        start = if (current == Route.SETUP_PAIR) com.carlink.app.ui.setup.SetupStep.PAIR
                            else com.carlink.app.ui.setup.SetupStep.WELCOME,
                        onExit = { stack = listOf(Route.HOME) })
                }
            }
        }
    }
}
