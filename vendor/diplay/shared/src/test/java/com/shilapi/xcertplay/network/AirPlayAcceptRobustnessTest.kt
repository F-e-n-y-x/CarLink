package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.*
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
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
class AirPlayAcceptRobustnessTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val clients = mutableListOf<Socket>()
    private val service = Robolectric.buildService(CarPlayVpnService::class.java).create().get()

    @After fun detach() {
        clients.forEach { runCatching { it.close() } }
        service.detach()
    }

    private fun attach(listener: AirPlaySessionListener) {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
        service.attachWireless(loopback, config, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
            PairingStore(), null, listener, object : AirPlayMediaHandler {}, listenerIdentity = AirPlayListenerIdentity(1))
    }

    private fun heldSlots(): Int =
        ReflectionHelpers.getField<AirPlayConnectionLimits>(service, "connectionLimits").openCount()

    private fun closedByServer(client: Socket): Boolean {
        client.soTimeout = 3_000
        return try { client.getInputStream().read() == -1 } catch (_: SocketTimeoutException) { false } catch (_: IOException) { true }
    }

    private fun connect(): Socket = Socket(loopback, service.boundPort()!!).also { clients.add(it) }

    @Test fun aThrowingListenerCallbackDoesNotEndTheAcceptLoop() {
        val calls = AtomicInteger()
        attach(object : AirPlaySessionListener {
            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (calls.incrementAndGet() == 1) throw IllegalStateException("listener failed")
            }
        })
        assertTrue("connection whose callback threw stayed open", closedByServer(connect()))
        val second = connect()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (calls.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("accept loop stopped after the first failure", 2, calls.get())
        assertTrue(second.isConnected)
    }

    @Test fun aShutDownWatchdogClosesTheConnectionAndKeepsTheLoopAlive() {
        attach(object : AirPlaySessionListener {})
        ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(service, "prePairWatchdog").shutdownNow()
        repeat(2) { assertTrue("connection ${it + 1} stayed open", closedByServer(connect())) }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (heldSlots() != 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("slots leaked", 0, heldSlots())
    }
}
