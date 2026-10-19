package com.carlink.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** The pre-flight's silent VPN consent over the stereo's adb, with a fake adb call. */
class AdbVpnConsentTest {
    @Test fun adbGrantSkipsTheDialog() {
        var consent = false
        var calls = 0
        val tried = AtomicBoolean(false)
        assertTrue(AdbVpnConsent.ensure({ consent }, tried, 1_000) { calls++; consent = true })
        assertTrue(AdbVpnConsent.ensure({ consent }, tried, 1_000) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun noAdbFallsBackToTheDialogAndIsNotRetried() {
        var calls = 0
        val tried = AtomicBoolean(false)
        assertFalse(AdbVpnConsent.ensure({ false }, tried, 1_000) { calls++; error("connection refused") })
        assertFalse(AdbVpnConsent.ensure({ false }, tried, 1_000) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun alreadyGrantedNeverTouchesAdb() {
        val tried = AtomicBoolean(false)
        assertTrue(AdbVpnConsent.ensure({ true }, tried, 1_000) { error("adb must not be used") })
        assertFalse(tried.get())
    }

    @Test fun aHangingAdbIsWaitedForAtMostTheBudget() {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        assertFalse(AdbVpnConsent.ensure({ false }, AtomicBoolean(false), 200) { release.await() })
        val waited = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertTrue("waited $waited ms", waited in 150..900)
    }
}
