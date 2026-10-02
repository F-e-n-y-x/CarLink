package com.shilapi.xcertplay

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayRemoteKeysTest {
    @Test
    fun unrelatedKeyIsNotConsumedWithoutController() {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INFO)
        assertFalse(CarPlayRemoteKeys.dispatch(event, null))
    }
}
