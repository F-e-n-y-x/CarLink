package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayPrePairWatchdogTest {
    @Test fun watchdogTaskIsCancelledWhenTheSessionEndsEarly() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val loopback = InetAddress.getByName("127.0.0.1")
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        try {
            service.attachWireless(loopback, config, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
                PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                listenerIdentity = AirPlayListenerIdentity(1))
            val watchdog = ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(service, "prePairWatchdog")
            Socket(loopback, service.boundPort()!!).close()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (watchdog.queue.size != 0 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals("watchdog tasks still queued after the session ended", 0, watchdog.queue.size)
        } finally { service.detach() }
    }

    // The first pairing runs SRP-3072 and Ed25519 in Java; slow head units need seconds before the iPhone proves its key.
    @Test fun anIdleConnectionGetsThirtySecondsToPairVerify() {
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()
        val loopback = InetAddress.getByName("127.0.0.1")
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        try {
            service.attachWireless(loopback, config, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
                PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                listenerIdentity = AirPlayListenerIdentity(1))
            val watchdog = ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(service, "prePairWatchdog")
            val idle = Socket(loopback, service.boundPort()!!)
            try {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (watchdog.queue.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
                val delayMillis = (watchdog.queue.single() as Delayed).getDelay(TimeUnit.MILLISECONDS)
                assertTrue("watchdog fires in $delayMillis ms", delayMillis in 25_000L..30_000L)
            } finally { idle.close() }
        } finally { service.detach() }
    }
}
