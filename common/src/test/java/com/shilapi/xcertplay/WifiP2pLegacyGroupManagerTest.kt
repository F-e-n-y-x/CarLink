package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiP2pManager
import java.net.InetAddress
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [WifiP2pLegacyGroupManagerTest.P2pRadio::class, WifiP2pLegacyGroupManagerTest.P2pChannel::class])
class WifiP2pLegacyGroupManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(context.getSystemService(WifiP2pManager::class.java)) as P2pRadio
    private val memory get() = context.getSharedPreferences("carplay_wifi_p2p_success", Context.MODE_PRIVATE)

    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val wifi = context.getSystemService(WifiManager::class.java)
        wifi.isWifiEnabled = true
        shadowOf(wifi.connectionInfo).setFrequency(5180)
        shadowOf(wifi.connectionInfo).setSupplicantState(SupplicantState.COMPLETED)
    }

    @Test fun selectedChannelIsRequestedThroughTheHiddenApiAndReportedUnverified() {
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add, preferredChannel = 149).use { manager ->
            val info = background { manager.start(5000) }
            assertEquals(6, radio.listeningChannel)
            assertEquals(149, radio.operatingChannel)
            assertEquals(1, radio.systemCreations)
            assertEquals("DIRECT-legacy-test", info.ssid)
            assertEquals(149, info.channel)
            assertEquals(5745, info.frequencyMHz)
            assertEquals("5 GHz", info.bandLabel)
            assertTrue(logs.any { it.contains("legacy channel applied channel=149 frequencyMHz=5745") })
            assertTrue(logs.any { it.contains("verified=false") })
            assertTrue(logs.any { it.contains("actualMHz=unavailable matched=unverified") })
            manager.onCarPlayConfirmed()
        }
        assertNull(memory.getString("confirmed", null))
    }

    @Test fun automaticStartPinsFiveGhzAndKeepsItsChannelUnknownWhenTheFrameworkRefuses() {
        radio.rejectChannels = true
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            val info = background { manager.start(8000) }
            assertEquals(1, radio.systemCreations)
            assertEquals(0, info.channel)
            assertNull(info.frequencyMHz)
            assertEquals("Auto", info.bandLabel)
            assertTrue(logs.any { it.contains("legacy channel rejected code=") })
        }
    }

    @Test fun rejectedSelectedChannelFailsInsteadOfFallingBackToTwoGhz() {
        radio.rejectChannels = true
        WifiP2pGroupManager(context, preferredChannel = 149).use { manager ->
            val error = failure { manager.start(5000) }
            assertTrue(error.message!!.contains("channel 149"))
        }
        assertEquals(0, radio.systemCreations)
        assertNull(memory.getString("confirmed", null))
    }

    private fun failure(block: () -> Any): Throwable {
        try { background(block); fail("Expected failure") }
        catch (failure: ExecutionException) { return failure.cause!! }
        error("unreachable")
    }

    private fun <T> background(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { block() }.get(20, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    @Implements(WifiP2pManager.Channel::class)
    class P2pChannel {
        @Implementation protected fun close() {}
    }

    @Implements(WifiP2pManager::class)
    class P2pRadio : ShadowWifiP2pManager() {
        var rejectChannels = false
        var systemCreations = 0
        var group: WifiP2pGroup? = null
        var removals = 0

        @Implementation override fun setWifiP2pChannels(
            channel: WifiP2pManager.Channel,
            listenChannel: Int,
            operatingChannel: Int,
            listener: WifiP2pManager.ActionListener,
        ) {
            super.setWifiP2pChannels(channel, listenChannel, operatingChannel, listener)
            if (rejectChannels) listener.onFailure(WifiP2pManager.ERROR) else listener.onSuccess()
        }

        @Implementation override fun requestGroupInfo(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.GroupInfoListener,
        ) {
            listener.onGroupInfoAvailable(group)
        }

        @Implementation override fun requestConnectionInfo(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ConnectionInfoListener,
        ) {
            listener.onConnectionInfoAvailable(WifiP2pInfo().apply {
                groupFormed = true
                isGroupOwner = true
                groupOwnerAddress = InetAddress.getByName("192.168.49.1")
            })
        }

        @Implementation override fun createGroup(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ActionListener,
        ) {
            systemCreations++
            group = makeGroup()
            listener.onSuccess()
        }

        @Implementation override fun createGroup(
            channel: WifiP2pManager.Channel,
            config: WifiP2pConfig?,
            listener: WifiP2pManager.ActionListener,
        ) {
            group = makeGroup()
            listener.onSuccess()
        }

        @Implementation override fun removeGroup(
            channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ActionListener,
        ) {
            removals++
            group = null
            listener.onSuccess()
        }

        fun makeGroup() = WifiP2pGroup().apply {
            shadowOf(this).setIsGroupOwner(true)
            shadowOf(this).setNetworkName("DIRECT-legacy-test")
            shadowOf(this).setPassphrase("legacy-passphrase")
            shadowOf(this).setInterface("p2p0")
        }
    }
}