package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [BydCarPlayCallLifecycleTest.Shell::class])
class BydCarPlayCallLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val writer get() = ReflectionHelpers.getField<ExecutorService>(BydCarPlayCall, "writer")
    private fun drain() { writer.submit {}.get(5, TimeUnit.SECONDS) }
    private fun ringing(id: String) = Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
        u8(2, 2); string(4, id)
    }

    @Before fun setup() {
        drain()
        ReflectionHelpers.getField<CarPlayCallState>(BydCarPlayCall, "state").clear()
        ReflectionHelpers.setField(BydCarPlayCall, "shown", null)
        ReflectionHelpers.setField(BydCarPlayCall, "watcherToken", null)
        ReflectionHelpers.setField(BydCarPlayCall, "watcherRunning", false)
        BydCarPlayCall.attach(app)
        BydOutputSettings.setCarPlayCalls(app, false)
        Shell.commands.clear()
    }

    @After fun cleanup() { BydCarPlayCall.end(); drain() }

    @Test fun defaultOffTracksCallWithoutAnyShellOrWatcherMutation() {
        BydCarPlayCall.onFrame(ringing("a")); drain()
        assertNotNull(BydCarPlayCall.current())
        assertTrue(Shell.commands.isEmpty())
    }

    @Test fun backToBackCallsUseDifferentTokensForWritesWatcherAndCleanup() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(ringing("a")); drain()
        val first = tokenFrom(Shell.commands.first { it.contains(" ringing ") })
        assertTrue(Shell.commands.any { it.contains(" watch $first ${app.packageName} ") })
        BydCarPlayCall.end(); drain()
        assertTrue(Shell.commands.any { it.endsWith(" end - $first") })
        BydCarPlayCall.onFrame(ringing("b")); drain()
        val next = tokenFrom(Shell.commands.last { it.contains(" ringing ") })
        assertNotEquals(first, next)
        assertTrue(Shell.commands.any { it.contains(" watch $next ${app.packageName} ") })
        assertTrue(Shell.commands.none { it.contains("rm -f") })
    }

    @Test fun disablingBeforeQueuedUpdateExecutesPreventsLateCallWrites() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        writer.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            BydOutputSettings.setCarPlayCalls(app, true)
            BydCarPlayCall.onFrame(ringing("a"))
            BydOutputSettings.setCarPlayCalls(app, false)
            BydCarPlayCall.settingChanged(false)
        } finally { release.countDown() }
        drain()
        assertTrue(Shell.commands.isEmpty())
    }

    private fun tokenFrom(command: String): String =
        Regex("/data/local/tmp/diplay-carplay-call-[A-Za-z0-9_.]+-[0-9a-f-]{36}")
            .find(command)!!.value

    @Implements(BydAdbShell::class, isInAndroidSdk = false)
    class Shell {
        @Implementation fun run(context: Context, command: String): String? {
            commands.add(command)
            return "state=0"
        }
        companion object { val commands = CopyOnWriteArrayList<String>() }
    }
}
