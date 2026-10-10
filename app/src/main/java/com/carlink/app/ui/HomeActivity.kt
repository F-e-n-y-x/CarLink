package com.carlink.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.core.core.PhoneKind
import com.carlink.core.connection.Connection
import com.carlink.core.entry.UsbWatcher
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.UiProfileDetector
import com.carlink.app.ui.update.UpdateDialog
import com.carlink.app.ui.update.UpdateDialogActions
import com.carlink.app.update.UpdatePolicy
import com.carlink.app.update.UpdateState
import com.carlink.app.update.Updates
import com.carlink.app.update.showsDialog

/** CarLink's launcher and hub. Hosts every CarLink screen (see ui/CarLinkApp.kt). */
class HomeActivity : ComponentActivity() {
    private val vm: CarLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { consumeAutoPhone(intent); consumeRecovery(intent) }
        setContent {
            val display by vm.display.collectAsStateWithLifecycle()
            val profile = remember(display.uiProfileMode) { UiProfileDetector.detect(this, display.uiProfileMode) }
            CarLinkTheme(profile, display.themeMode, display.interfaceScale, display.liteRendering) {
                androidx.compose.foundation.layout.Box {
                    CarLinkApp(vm, onExitToCarHome = ::exitToCarHome)
                    UpdatePrompt()
                }
            }
        }
    }

    /**
     * The update dialog over every CarLink screen. An offer waits while a projection session is
     * starting or running (or a phone switch is asked); a download the driver started stays.
     */
    @androidx.compose.runtime.Composable
    private fun UpdatePrompt() {
        val updater = remember { Updates.get(this) }
        val state by updater.state.collectAsStateWithLifecycle()
        val connection = remember { Connection.get(this) }
        val session by connection.state.collectAsStateWithLifecycle()
        val pendingSwitch by connection.pendingSwitch.collectAsStateWithLifecycle()
        val quiet = UpdatePolicy.sessionQuiet(session, pendingSwitch)
        if (!state.showsDialog || (state is UpdateState.Available && !quiet)) return
        val actions = remember(updater) {
            object : UpdateDialogActions {
                override fun ignore() = updater.ignore()
                override fun later() = updater.later()
                override fun download() = updater.download()
                override fun cancel() = updater.cancel()
                override fun openSettings() = updater.openInstallPermission()
                override fun dismiss() = updater.dismiss()
            }
        }
        UpdateDialog(state, updater.current.toString(), actions)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(autoUpdateCheck)
        super.onPause()
    }

    /** At most every 6 hours, with Home in front, set up done and no session (UpdatePolicy). */
    private val autoUpdateCheck = Runnable {
        if (!UiPreferences(this).setupDone) return@Runnable
        val connection = Connection.get(this)
        Updates.get(this).maybeAutoCheck(UpdatePolicy.sessionQuiet(connection.state.value, connection.pendingSwitch.value))
    }

    override fun onResume() {
        super.onResume()
        // After Connection.onHomeShown below had its chance to start a session, and after startup.
        window.decorView.postDelayed(autoUpdateCheck, UPDATE_CHECK_DELAY_MILLIS)
        // After the first frames: the ":aa" process start competes with Home's first draw (CarLinkApplication.deferStartupWork).
        window.decorView.postDelayed({ Connection.prewarm(this) }, PREWARM_DELAY_MILLIS)
        Connection.onHomeShown(this)
        vm.refreshPhones()
        UsbWatcher.get(this).start()
    }

    /** Back on Home goes to the car's own launcher, as DiAuto's "Car home" did. */
    private fun exitToCarHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }.onFailure { moveTaskToBack(true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeAutoPhone(intent)
        consumeRecovery(intent)
    }

    /** A phone SystemEventReceiver identified; start its engine now that CarLink is in front. */
    /**
     * The CarPlay waiting screen's "Reset CarPlay Wi-Fi" opens Home with DiPlay's
     * page=wireless-recovery: stop the attempt and remove any Wi-Fi Direct group it left, so the
     * next Connect starts clean.
     */
    private fun consumeRecovery(intent: Intent) {
        if (intent.getStringExtra("page") != "wireless-recovery") return
        intent.removeExtra("page")
        Connection.get(this).stop()
        val app = applicationContext
        Thread({ com.carlink.core.engine.WifiDirectReset.removeGroup(app) }, "carlink-wifi-recovery").start()
    }

    private fun consumeAutoPhone(intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_AUTO_PHONE_KIND)
            ?.let { runCatching { PhoneKind.valueOf(it) }.getOrNull() } ?: return
        val address = intent.getStringExtra(EXTRA_AUTO_PHONE_ADDRESS) ?: return
        val name = intent.getStringExtra(EXTRA_AUTO_PHONE_NAME)
        intent.removeExtra(EXTRA_AUTO_PHONE_KIND)
        Connection.get(this).onBluetoothPhone(address, kind, name)
    }

    companion object {
        const val EXTRA_AUTO_PHONE_ADDRESS = "com.carlink.app.extra.AUTO_PHONE_ADDRESS"
        const val EXTRA_AUTO_PHONE_KIND = "com.carlink.app.extra.AUTO_PHONE_KIND"
        const val EXTRA_AUTO_PHONE_NAME = "com.carlink.app.extra.AUTO_PHONE_NAME"
        private const val PREWARM_DELAY_MILLIS = 2_500L
        private const val UPDATE_CHECK_DELAY_MILLIS = 8_000L
        fun intent(context: Context): Intent = Intent(context, HomeActivity::class.java)
    }
}
