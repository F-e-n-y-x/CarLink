package com.carlink.app.ui.home

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.app.connection.CarPlayWaitingView
import com.carlink.app.connection.Connection
import com.carlink.app.connection.toLinkState
import com.carlink.app.core.LinkProblem
import com.carlink.app.detect.BluetoothAvailability
import com.carlink.app.settings.CarLinkPreferences
import com.carlink.app.ui.ConnectionPrefs
import com.carlink.app.ui.HomeActivity
import com.carlink.app.ui.HomeState
import com.carlink.app.ui.Route
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.UiProfileDetector

/**
 * What CarPlay's projection window shows until the picture appears: Home's own connecting,
 * retrying and failure screen, from the same session state, so an iPhone looks exactly like an
 * Android phone while connecting (design/REBUILD_V2.md §7; DIPLAY.md F2).
 */
object CarPlayWaitingContent {
    fun register() = CarPlayWaitingView.register(::create)

    private fun create(context: Context): View = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        val session = Connection.get(context)
        val preferences = CarLinkPreferences(context)
        val actions = object : HomeActions {
            override fun disconnect() = session.disconnect()
            override fun retry() = session.retry()
            override fun navigate(route: Route) = openHome(context)
            override fun fix(problem: LinkProblem) = openHome(context)
            override fun openBluetoothSettings() = openHome(context)
        }
        setContent {
            val state by session.state.collectAsStateWithLifecycle()
            val profile = UiProfileDetector.detect(context, preferences.uiProfileMode)
            CarLinkTheme(profile, preferences.themeMode, preferences.interfaceScale) {
                val link = state.toLinkState()
                HomeContent(
                    HomeUi(
                        home = HomeState(link, emptyList(), BluetoothAvailability.READY, null, null),
                        connection = ConnectionPrefs(preferences.autoConnectUsb, preferences.autoConnectBluetooth,
                            preferences.startOnBoot, preferences.autoConnectPaused),
                        network = null,
                        permissionsNeeded = false,
                    ),
                    actions,
                )
            }
        }
    }

    /** Fixes and settings live in CarLink's Home; the attempt keeps its state there. */
    private fun openHome(context: Context) {
        context.startActivity(Intent(context, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}
