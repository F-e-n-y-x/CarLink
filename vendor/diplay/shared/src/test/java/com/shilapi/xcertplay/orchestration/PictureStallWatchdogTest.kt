package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.CarHotspotIpFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CarLink: "linked, then stuck at Picture" on a dual-stack car hotspot is a failure.
class PictureStallWatchdogTest {
    private class FakeTimer {
        var pending: (() -> Unit)? = null
        var delay: Long = -1
        var cancelled = 0
        val schedule: (Long, () -> Unit) -> (() -> Unit) = { d, action ->
            delay = d; pending = action
            val cancel: () -> Unit = { cancelled++; pending = null }
            cancel
        }
        fun fire() { pending?.let { pending = null; it() } }
    }

    private class MapStore : CarHotspotIpFamily.Memory.Store {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun firstTcpThenNoPictureFiresTheStall() {
        val timer = FakeTimer()
        var stalls = 0
        val dog = PictureStallWatchdog(CarHotspotIpFamily.PICTURE_STALL_MILLIS, timer.schedule) { stalls++ }
        assertFalse(dog.armed)
        dog.arm() // first AirPlay TCP / RTSP SETUP
        assertTrue(dog.armed)
        assertEquals(CarHotspotIpFamily.PICTURE_STALL_MILLIS, timer.delay)
        timer.fire() // Picture / NTP / event / RTP ports never connected
        assertEquals(1, stalls)
        assertFalse(dog.armed)
        dog.arm(); timer.fire()
        assertEquals("fires once per session", 1, stalls)
    }

    @Test fun aRenderedFrameCancelsTheStall() {
        val timer = FakeTimer()
        var stalls = 0
        val dog = PictureStallWatchdog(1_000L, timer.schedule) { stalls++ }
        dog.arm()
        dog.frameRendered()
        assertEquals(1, timer.cancelled)
        timer.fire()
        assertEquals(0, stalls)
    }

    @Test fun teardownCancelsAndArmingTwiceSchedulesOnce() {
        val timer = FakeTimer()
        var schedules = 0
        val counting: (Long, () -> Unit) -> (() -> Unit) = { d, a -> schedules++; timer.schedule(d, a) }
        val dog = PictureStallWatchdog(1_000L, counting) { error("must not stall") }
        dog.arm(); dog.arm()
        assertEquals(1, schedules)
        dog.terminate()
        timer.fire()
        dog.arm()
        assertEquals(1, schedules)
    }

    /** The whole fallback: dual stack → TCP → no picture → IPv4 only for this unit, next run IPv4 only. */
    @Test fun stuckAtPictureFallsBackToIpv4OnlyAndRemembersTheUnit() {
        val store = MapStore()
        val memory = CarHotspotIpFamily.Memory(store) { 42L }
        val unit = CarHotspotIpFamily.unitKey("CarAP", "wlan0", "model")
        val v6 = java.net.InetAddress.getByName("2001:db8::5") as java.net.Inet6Address
        val probe = CarHotspotIpFamily.Probe(true,
            listOf(CarHotspotIpFamily.Ipv6Candidate(v6, false, false, false, bindOk = true)), false, true)

        val first = CarHotspotIpFamily.decide(probe, memory.read(unit))
        assertEquals(CarHotspotIpFamily.MODE_DUAL_STACK, first.mode)

        val timer = FakeTimer()
        var restarted = false
        val dog = PictureStallWatchdog(CarHotspotIpFamily.PICTURE_STALL_MILLIS, timer.schedule) {
            memory.recordIpv6Failure(unit, CarHotspotIpFamily.REASON_FALLBACK_PICTURE_STALL)
            restarted = true
        }
        dog.arm() // RTSP SETUP succeeded
        timer.fire() // ...but the picture never came
        assertTrue(restarted)

        val retry = CarHotspotIpFamily.decide(probe, memory.read(unit))
        assertEquals(CarHotspotIpFamily.MODE_IPV4_ONLY, retry.mode)
        assertEquals(CarHotspotIpFamily.REASON_REMEMBERED_IPV4, retry.reason)
        assertTrue(retry.ipv6Addresses.isEmpty())
        assertEquals(CarHotspotIpFamily.REASON_FALLBACK_PICTURE_STALL, memory.read(unit)!!.reason)
    }
}
