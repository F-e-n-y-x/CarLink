package com.carlink.core.settings

import android.view.KeyEvent
import com.carlink.core.connection.CarPlaySettingsPolicy
import com.carlink.core.connection.aa.AaSessionSettings
import com.carlink.core.core.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelKeysTest {
    @Test fun oneKeyPerActionAndOneActionPerKey() {
        var keys = WheelKeys.assign(emptyMap(), WheelAction.VOICE, 84)
        keys = WheelKeys.assign(keys, WheelAction.VOICE, 300)
        assertEquals(mapOf(WheelAction.VOICE to 300), keys)
        // Learning the same button for another action moves it.
        keys = WheelKeys.assign(keys, WheelAction.ANSWER, 300)
        assertEquals(mapOf(WheelAction.ANSWER to 300), keys)
    }

    @Test fun backVolumeHomeAndPowerCannotBeLearnt() {
        for (code in listOf(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, -1)) {
            assertFalse("$code", WheelKeys.canLearn(code))
        }
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_DPAD_CENTER, 312, 3001)) {
            assertTrue("$code", WheelKeys.canLearn(code))
        }
    }

    @Test fun translatesToTheStandardKeysBothEnginesHandle() {
        val keys = mapOf(WheelAction.VOICE to 84, WheelAction.ANSWER to 600, WheelAction.HANG_UP to 601,
            WheelAction.PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, WheelAction.NEXT to 88, WheelAction.PREVIOUS to 87)
        val remap = mapOf(84 to KeyEvent.KEYCODE_VOICE_ASSIST, 600 to KeyEvent.KEYCODE_CALL, 601 to KeyEvent.KEYCODE_ENDCALL,
            88 to KeyEvent.KEYCODE_MEDIA_NEXT, 87 to KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        // Play/pause already is the play/pause key: nothing to translate.
        assertEquals(remap, WheelKeys.remap(keys))
        // DiAuto's keymap is "logical-physical".
        assertEquals(setOf("231-84", "5-600", "6-601", "87-88", "88-87"), WheelKeys.diAutoKeyCodes(keys))
        assertTrue(WheelKeys.needsCarPlayCallKeys(keys))
        assertFalse(WheelKeys.needsCarPlayCallKeys(mapOf(WheelAction.NEXT to 88)))
    }

    @Test fun encodeDecodeRoundTripsAndDropsJunk() {
        val keys = mapOf(WheelAction.VOICE to 84, WheelAction.PREVIOUS to 3002)
        assertEquals(keys, WheelKeys.decode(WheelKeys.encode(keys)))
        assertEquals(emptyMap<WheelAction, Int>(), WheelKeys.decode(null))
        assertEquals(emptyMap<WheelAction, Int>(), WheelKeys.decode(""))
        assertEquals(mapOf(WheelAction.NEXT to 87), WheelKeys.decode("NEXT=87,ZOOM=5,VOICE=x,ANSWER=4,junk"))
    }

    @Test fun bothEnginesReceiveTheMapping() {
        val settings = UnifiedSettings(wheelKeys = mapOf(WheelAction.VOICE to 84, WheelAction.HANG_UP to 601))
        val aa = AaSessionSettings.values(settings, Transport.USB, null, VehicleProfile.GENERIC, "Car", 160).values
        assertEquals(setOf("231-84", "6-601"), aa["key-codes"])
        // Nothing learnt: DiAuto's keymap is cleared, not left from an earlier session.
        assertEquals(emptySet<String>(), AaSessionSettings.values(UnifiedSettings(), Transport.USB, null, VehicleProfile.GENERIC,
            "Car", 160).values["key-codes"])

        val cp = CarPlaySettingsPolicy.map(settings, null, VehicleProfile.GENERIC, true)
        assertEquals(mapOf(84 to KeyEvent.KEYCODE_VOICE_ASSIST, 601 to KeyEvent.KEYCODE_ENDCALL), cp.wheelKeyRemap)
        assertTrue(cp.callKeys)
        assertFalse(CarPlaySettingsPolicy.map(UnifiedSettings(), null, VehicleProfile.GENERIC, true).callKeys)
    }
}
