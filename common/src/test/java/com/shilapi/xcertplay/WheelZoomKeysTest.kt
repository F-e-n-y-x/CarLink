package com.shilapi.xcertplay

import com.shilapi.xcertplay.WheelZoomKeys.Action
import com.shilapi.xcertplay.WheelZoomSettings.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelZoomKeysTest {
    private fun WheelZoomKeys.press(role: Role?, mapShown: Boolean = true, inCall: Boolean = false): Pair<Action, Action> =
        onKey(role, down = true, firstPress = true, mapShown = mapShown, inCall = inCall) to
            onKey(role, down = false, firstPress = true, mapShown = mapShown, inCall = inCall)

    @Test
    fun modeKeyTogglesZoomAndVolumeKeysFollowIt() {
        val keys = WheelZoomKeys()
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN))
        assertEquals(Action.MODE_ON to Action.CONSUME, keys.press(Role.MODE))
        assertEquals(Action.ZOOM_IN to Action.CONSUME, keys.press(Role.ZOOM_IN))
        assertEquals(Action.ZOOM_OUT to Action.CONSUME, keys.press(Role.ZOOM_OUT))
        assertEquals(Action.MODE_OFF to Action.CONSUME, keys.press(Role.MODE))
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_OUT))
    }

    @Test
    fun otherKeysAlwaysPass() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.PASS to Action.PASS, keys.press(null))
    }

    @Test
    fun withoutTheDashboardMapTheModeKeyKeepsItsCarActionAndEndsZoom() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertTrue(keys.zoomMode)
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.MODE, mapShown = false))
        assertFalse(keys.zoomMode)
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN))
    }

    @Test
    fun aCallKeepsTheVolumeKeysForVolume() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.PASS to Action.PASS, keys.press(Role.ZOOM_IN, inCall = true))
        assertTrue(keys.zoomMode)
        assertEquals(Action.ZOOM_IN to Action.CONSUME, keys.press(Role.ZOOM_IN))
    }

    @Test
    fun autoRepeatsAreKeptButDoNotZoomAgain() {
        val keys = WheelZoomKeys()
        keys.press(Role.MODE)
        assertEquals(Action.CONSUME, keys.onKey(Role.ZOOM_IN, down = true, firstPress = false, mapShown = true, inCall = false))
        assertEquals(Action.CONSUME, keys.onKey(Role.MODE, down = true, firstPress = false, mapShown = true, inCall = false))
        assertTrue(keys.zoomMode)
    }

    @Test
    fun timeOutEndsZoomModeOnce() {
        val keys = WheelZoomKeys()
        assertFalse(keys.timeOut())
        keys.press(Role.MODE)
        assertTrue(keys.timeOut())
        assertFalse(keys.zoomMode)
        assertFalse(keys.timeOut())
    }

    @Test
    fun keysSurviveTheirStoredForm() {
        val key = WheelKey(305, 300, "simulate-keys")
        assertEquals(key, WheelKey.decode(key.encode()))
        // Only the first two separators split; a device name may contain one.
        assertEquals(WheelKey(291, 114, "a|b"), WheelKey.decode("291|114|a|b"))
        assertNull(WheelKey.decode(null))
        assertNull(WheelKey.decode("292|115"))
        assertNull(WheelKey.decode("x|115|simulate-keys"))
    }
}
