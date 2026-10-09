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
import com.carlink.app.core.PhoneKind
import com.carlink.app.connection.Connection
import com.carlink.app.entry.UsbWatcher
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.UiProfileDetector

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
                CarLinkApp(vm, onExitToCarHome = ::exitToCarHome)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Connection.prewarm(this)
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
        Thread({ com.carlink.app.engine.WifiDirectReset.removeGroup(app) }, "carlink-wifi-recovery").start()
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
        fun intent(context: Context): Intent = Intent(context, HomeActivity::class.java)
    }
}
