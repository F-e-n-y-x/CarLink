package com.carlink.core.settings

import android.app.Application
import android.os.Build
import android.os.Looper
import android.view.KeyEvent
import com.andrerinas.openheadunit.HostIntegration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 35], application = Application::class)
class WheelMediaLearningTest {
    @Test fun androidAutoMediaNextIsLearnedAndTheInterceptorIsRemovedOnClose() {
        val app = RuntimeEnvironment.getApplication()
        // The tested APK merges AndroidX's signature permission. The library test APK has its own ID.
        if (Build.VERSION.SDK_INT < 33) {
            shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        }
        val main = shadowOf(Looper.getMainLooper())
        val learned = mutableListOf<Int>()
        WheelMediaLearning.installAndroidAuto(app)
        WheelMediaLearning.listen(app, 15_000, learned::add).use {
            main.idle()
            val capture = checkNotNull(HostIntegration.onLearnMediaButton)
            val nextDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
            val nextUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
            assertFalse(capture(nextUp)) // The press must have started during Learn.
            assertFalse(capture(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
            assertTrue(capture(nextDown))
            main.idle()
            assertTrue(learned.isEmpty())
            assertTrue(capture(nextUp))
            main.idle()
            assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT), learned)
        }
        main.idle()
        assertNull(HostIntegration.onLearnMediaButton)
    }
}
