package com.carlink.app.ui.settings

import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfile
import com.carlink.core.settings.WheelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Learn takes the next wheel key: on its release, never a release whose press it did not see. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1024dp-h600dp-land-mdpi")
class LearnPanelTest {
    @get:Rule val rule = createComposeRule()

    private val learnt = mutableListOf<Int>()
    private var cancelled = false
    private var timedOut = false

    private fun show() = rule.setContent {
        CarLinkTheme(profile = UiProfile.CAR, themeMode = ThemeMode.DARK) {
            LearnPanel(WheelAction.VOICE, onKey = { learnt += it }, onTimeout = { timedOut = true }, onCancel = { cancelled = true })
        }
    }

    private fun send(action: Int, code: Int) =
        rule.onNodeWithTag(LEARN_PANEL_TAG).performKeyPress(ComposeKeyEvent(KeyEvent(action, code)))

    @Test fun learnsAPressedKeyOnItsRelease() {
        show()
        send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER) // the release of the Enter that pressed Learn
        send(KeyEvent.ACTION_DOWN, 600)
        assertTrue(learnt.isEmpty())
        send(KeyEvent.ACTION_UP, 600)
        assertEquals(listOf(600), learnt)
    }

    @Test fun backCancelsAndVolumePassesOn() {
        show()
        send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)
        send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        assertTrue(learnt.isEmpty())
        assertTrue(cancelled)
    }

    @Test fun givesUpAfterTheTimeout() {
        show()
        rule.mainClock.advanceTimeBy(WHEEL_LEARN_SECONDS * 1_000L + 100)
        assertTrue(timedOut)
    }
}
