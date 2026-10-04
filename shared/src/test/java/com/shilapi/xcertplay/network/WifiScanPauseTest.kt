package com.shilapi.xcertplay.network

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WifiScanPauseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ok = "Result: Parcel(00000000    '....')"

    private fun pause(code: Int?, reply: String?, commands: MutableList<String>): Pair<WifiScanPause, java.util.concurrent.ExecutorService> {
        val executor = Executors.newSingleThreadExecutor()
        val pause = WifiScanPause(context, {}, { code }, { commands.add(it); reply }, executor)
        return pause to executor
    }

    @Test
    fun pausesOnceAndRestoresOnClose() {
        val commands = CopyOnWriteArrayList<String>()
        val (pause, executor) = pause(62, ok, commands)
        pause.pause()
        pause.pause()
        pause.close()
        pause.pause()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("service call wifi 62 i32 0", "service call wifi 62 i32 1"), commands)
    }

    @Test
    fun doesNothingWithoutTheTransactionCode() {
        val commands = CopyOnWriteArrayList<String>()
        val (pause, executor) = pause(null, ok, commands)
        pause.pause()
        pause.close()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun doesNotRestoreScansItNeverPaused() {
        val commands = CopyOnWriteArrayList<String>()
        val (pause, executor) = pause(62, null, commands)
        pause.pause()
        pause.close()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("service call wifi 62 i32 0"), commands)
    }

    @Test
    fun acceptsOnlyAVoidReplyWithoutException() {
        assertTrue(WifiScanPause.accepted(ok))
        assertFalse(WifiScanPause.accepted("Result: Parcel(ffffffec 00000000 '........')"))
        assertFalse(WifiScanPause.accepted("service: Service wifi does not exist"))
        assertFalse(WifiScanPause.accepted(null))
    }

    @Test
    fun readsTheTransactionCodeFromTheFramework() {
        val expected = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getDeclaredField("TRANSACTION_enableWifiConnectivityManager")
            .apply { isAccessible = true }
            .getInt(null)
        assertEquals(expected, WifiScanPause.enableConnectivityManagerTransaction())
    }

    @Test
    @Config(sdk = [30])
    fun skipsReleasesAfterAndroid10() {
        assertNull(WifiScanPause.enableConnectivityManagerTransaction())
    }
}
