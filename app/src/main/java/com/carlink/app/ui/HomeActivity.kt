package com.carlink.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.carlink.app.core.PhoneKind
import com.carlink.app.engine.EngineCoordinator

/** CarLink's launcher and hub. Hosts every CarLink screen (see ui/CarLinkApp.kt). */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) consumeAutoPhone(intent)
        setContent { Text("CarLink") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeAutoPhone(intent)
    }

    /** A phone SystemEventReceiver identified; start its engine now that CarLink is in front. */
    private fun consumeAutoPhone(intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_AUTO_PHONE_KIND)
            ?.let { runCatching { PhoneKind.valueOf(it) }.getOrNull() } ?: return
        val address = intent.getStringExtra(EXTRA_AUTO_PHONE_ADDRESS) ?: return
        val name = intent.getStringExtra(EXTRA_AUTO_PHONE_NAME)
        intent.removeExtra(EXTRA_AUTO_PHONE_KIND)
        EngineCoordinator.get(this).onBluetoothPhone(address, kind, name)
    }

    companion object {
        const val EXTRA_AUTO_PHONE_ADDRESS = "com.carlink.app.extra.AUTO_PHONE_ADDRESS"
        const val EXTRA_AUTO_PHONE_KIND = "com.carlink.app.extra.AUTO_PHONE_KIND"
        const val EXTRA_AUTO_PHONE_NAME = "com.carlink.app.extra.AUTO_PHONE_NAME"

        fun intent(context: Context): Intent = Intent(context, HomeActivity::class.java)
    }
}
