package com.carlink.core.entry

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every boot and ignition wake-up the receiver acts on is also in its manifest filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SystemEventReceiverTest {
    @Test fun everyBootActionReachesTheReceiver() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue("com.fyt.boot.ACCON" in SystemEventReceiver.BOOT_ACTIONS)
        for (action in SystemEventReceiver.BOOT_ACTIONS) {
            val receivers = context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertTrue(action, receivers.any { it.activityInfo.name == SystemEventReceiver::class.java.name })
        }
    }
}
