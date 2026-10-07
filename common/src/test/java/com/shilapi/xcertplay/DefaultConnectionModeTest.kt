package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class DefaultConnectionModeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @Test fun existingInstallationsKeepLastUsedBehaviorAndUnknownValuesFallBack() {
        for (lastWireless in listOf(false, true)) {
            AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
            app.getSharedPreferences("diplay", 0).edit()
                .putString("default_connection_mode", "future_mode").commit()
            assertEquals(DefaultConnectionMode.LAST_USED, DiPlayPreferences.defaultConnectionMode(app))
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
        }
    }

    @Test fun fixedDefaultSurvivesManualTransportChangesWithoutChangingAutoConnectOrPhone() {
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        val expectedModes = mapOf(
            DefaultConnectionMode.LAST_USED to listOf(false, true),
            DefaultConnectionMode.WIRELESS to listOf(true, true),
            DefaultConnectionMode.USB to listOf(false, false),
        )
        for ((mode, expectedWireless) in expectedModes) {
            DiPlayPreferences.saveDefaultConnectionMode(app, mode)
            assertEquals(mode, DiPlayPreferences.defaultConnectionMode(app))
            for ((index, lastWireless) in listOf(false, true).withIndex()) {
                AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
                assertEquals(expectedWireless[index], DiPlayPreferences.autoConnectWireless(app))
                assertEquals(lastWireless, AirPlayPersistence.loadWirelessEnabled(app))
            }
            assertFalse(DiPlayPreferences.autoConnect(app))
            assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        }
    }

    @Test fun settingsSavePersistsChoiceAndCancelKeepsSavedDefault() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val controls = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("connectionSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, controls)
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val button = descendants(controls).filterIsInstance<Button>().single {
            it.text.toString().startsWith(activity.getString(R.string.default_connection_mode) + " · ")
        }
        button.performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 2, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DefaultConnectionMode.USB, DiPlayPreferences.defaultConnectionMode(app))
        assertTrue(button.text.toString().endsWith(activity.getString(R.string.default_connection_usb)))
        button.performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 0)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DefaultConnectionMode.USB, DiPlayPreferences.defaultConnectionMode(app))
        assertFalse(DiPlayPreferences.autoConnect(app))
    }
}
