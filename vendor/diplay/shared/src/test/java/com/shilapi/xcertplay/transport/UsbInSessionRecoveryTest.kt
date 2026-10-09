package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** CarLink: in-session recovery of USBMUX and NCM transfers, through the real pipes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE,
    shadows = [RecoveryUsbConnectionShadow::class, RecoveryUsbRequestShadow::class])
class UsbInSessionRecoveryTest {
    @Before fun reset() { RecoveryReplay.reset() }

    @Test fun usbmuxTimedOutReadKeepsItsRequestAndDeliversTheLateBytes() {
        val pipe = pipe(alive = true)
        val cancels: Int
        try {
            RecoveryReplay.reaps.add(Reap.Timeout)
            assertEquals(null, pipe.read(50))
            RecoveryReplay.reaps.add(Reap.Data(byteArrayOf(1, 2, 3)))
            assertArrayEquals(byteArrayOf(1, 2, 3), pipe.read(50))
            cancels = RecoveryReplay.cancels
        } finally { pipe.close() }
        assertEquals("one request queued once", 1, RecoveryReplay.queues)
        assertEquals("a timed-out read is not cancelled", 0, cancels)
    }

    @Test fun usbmuxNullReapIsRetriedWhileThePhoneAnswers() {
        val diagnostics = CopyOnWriteArrayList<String>()
        val pipe = pipe(alive = true, diagnostics = diagnostics)
        try {
            RecoveryReplay.reaps.addAll(listOf(Reap.Null, Reap.Null, Reap.Data(byteArrayOf(9))))
            assertArrayEquals(byteArrayOf(9), pipe.read(1_000))
        } finally { pipe.close() }
        assertEquals(2, diagnostics.count { it.startsWith("USBMUX reap returned no request") })
    }

    @Test fun usbmuxNullReapFailsAtOnceWhenThePhoneIsGone() {
        val pipe = pipe(alive = false)
        try {
            RecoveryReplay.reaps.add(Reap.Null)
            expectUnavailable("Android returned no USBMUX read request") { pipe.read(1_000) }
        } finally { pipe.close() }
    }

    @Test fun usbmuxRepeatedNullReapsStillFail() {
        val pipe = pipe(alive = true)
        try {
            repeat(UsbTransientFailureBudget.MAX_CONSECUTIVE + 1) { RecoveryReplay.reaps.add(Reap.Null) }
            expectUnavailable("Android returned no USBMUX read request") { pipe.read(5_000) }
        } finally { pipe.close() }
    }

    @Test fun usbmuxSinglePacketWriteIsRetriedOnce() {
        val pipe = pipe(alive = true)
        try {
            RecoveryReplay.writeResults.addAll(listOf(-1, 36))
            pipe.write(ByteArray(36), 100)
        } finally { pipe.close() }
        assertEquals(2, RecoveryReplay.writes)
    }

    @Test fun usbmuxWriteIsNotRetriedWhenThePhoneIsGoneOrTheWriteSpansPackets() {
        val gone = pipe(alive = false)
        try {
            RecoveryReplay.writeResults.add(-1)
            expectUnavailable("USBMUX write transferred -1 of 36 bytes") { gone.write(ByteArray(36), 100) }
        } finally { gone.close() }
        assertEquals(1, RecoveryReplay.writes)
        RecoveryReplay.reset()
        val large = pipe(alive = true)
        try {
            RecoveryReplay.writeResults.add(-1)
            expectUnavailable("USBMUX write transferred -1 of 600 bytes") { large.write(ByteArray(600), 100) }
        } finally { large.close() }
        assertEquals(1, RecoveryReplay.writes)
    }

    @Test fun usbmuxWriteFailsWhenTheRetryFailsToo() {
        val pipe = pipe(alive = true)
        try {
            RecoveryReplay.writeResults.addAll(listOf(-1, -1))
            expectUnavailable("USBMUX write transferred -1 of 20 bytes") { pipe.write(ByteArray(20), 100) }
        } finally { pipe.close() }
        assertEquals(2, RecoveryReplay.writes)
    }

    @Test fun ncmKeepsRefusedFramesAndSendsThemOnLinkUp() {
        val events = CopyOnWriteArrayList<NcmLinkEvent>()
        RecoveryReplay.refuseWrites = true
        val ncm = ncm(events)
        try {
            ncm.send(byteArrayOf(1, 2, 3), 2_000)
            waitUntil { events.contains(NcmLinkEvent.BulkOutNotReady) }
            RecoveryReplay.refuseWrites = false
            // The phone reports NETWORK_CONNECTION connected=1 on the status endpoint.
            RecoveryReplay.statusNotification = byteArrayOf(0xa1.toByte(), 0, 1, 0, 4, 0, 0, 0)
            RecoveryReplay.reaps.add(Reap.Status)
            assertEquals(null, ncm.recv(100))
            waitUntil { events.any { it is NcmLinkEvent.BulkOutReady } }
        } finally { ncm.close() }
        assertTrue(events.contains(NcmLinkEvent.NetworkConnection(true)))
        val accepted = RecoveryReplay.acceptedBlocks.single()
        assertArrayEquals(byteArrayOf(1, 2, 3), Ntb16Codec.parse(accepted, 0, accepted.size).single())
    }

    @Test fun ncmStatusAndDataCompletionsShareTheReader() {
        val events = CopyOnWriteArrayList<NcmLinkEvent>()
        val ncm = ncm(events)
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        try {
            RecoveryReplay.statusNotification = byteArrayOf(0xa1.toByte(), 0, 1, 0, 4, 0, 0, 0)
            RecoveryReplay.reaps.addAll(listOf(Reap.Status, Reap.Data(Ntb16Codec.build(frame, 1))))
            assertArrayEquals(frame, ncm.recv(1_000))
        } finally { ncm.close() }
        assertEquals(listOf<NcmLinkEvent>(NcmLinkEvent.NetworkConnection(true)), events.toList())
        assertEquals("status request is queued again after its completion", 2, RecoveryReplay.statusQueues)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("condition not reached")
            Thread.sleep(5)
        }
    }

    private fun expectUnavailable(message: String, block: () -> Unit) {
        try { block(); fail("expected failure") }
        catch (error: IphoneUsbException.DeviceUnavailable) { assertEquals(message, error.message) }
    }

    private fun pipe(alive: Boolean, diagnostics: MutableList<String> = mutableListOf()) =
        Iap2UsbSession(connection(), endpoint(0x04, 2), endpoint(0x85, 2), { diagnostics.add(it) }, { alive })

    private fun ncm(events: MutableList<NcmLinkEvent>) = NcmUsbBridge(
        connection(), endpoint(0x06, 2), endpoint(0x85, 2), endpoint(0x87, 3), emptyList(), null,
        onLinkEvent = { events.add(it) }, isDeviceAlive = { true },
    )

    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int, type: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, type),
        ClassParameter.from(Int::class.javaPrimitiveType, if (type == 3) 16 else 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}

sealed class Reap {
    data object Timeout : Reap()
    data object Null : Reap()
    data object Status : Reap()
    class Data(val bytes: ByteArray) : Reap()
}

object RecoveryReplay {
    val reaps = ArrayDeque<Reap>()
    val writeResults = ArrayDeque<Int>()
    val acceptedBlocks = CopyOnWriteArrayList<ByteArray>()
    @Volatile var refuseWrites = false
    @Volatile var writes = 0
    var queues = 0
    var statusQueues = 0
    var cancels = 0
    var dataRequest: UsbRequest? = null
    var dataBuffer: ByteBuffer? = null
    var statusRequest: UsbRequest? = null
    var statusBuffer: ByteBuffer? = null
    var statusNotification: ByteArray? = null
    val endpoints = HashMap<UsbRequest, Int>()
    fun reset() {
        reaps.clear(); writeResults.clear(); acceptedBlocks.clear(); refuseWrites = false; writes = 0
        queues = 0; statusQueues = 0; cancels = 0; dataRequest = null; dataBuffer = null
        statusRequest = null; statusBuffer = null; statusNotification = null; endpoints.clear()
    }
}

@Implements(UsbRequest::class)
class RecoveryUsbRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        synchronized(RecoveryReplay) { RecoveryReplay.endpoints[request] = endpoint.type }
        return true
    }
    @Implementation(minSdk = 26) fun queue(buffer: ByteBuffer): Boolean = synchronized(RecoveryReplay) {
        if (RecoveryReplay.endpoints[request] == 3) {
            RecoveryReplay.statusQueues++
            RecoveryReplay.statusRequest = request
            RecoveryReplay.statusBuffer = buffer
        } else {
            RecoveryReplay.queues++
            RecoveryReplay.dataRequest = request
            RecoveryReplay.dataBuffer = buffer
        }
        true
    }
    @Implementation fun cancel(): Boolean { RecoveryReplay.cancels++; return true }
    @Implementation fun close() = Unit
}

@Implements(UsbDeviceConnection::class)
class RecoveryUsbConnectionShadow {
    @Implementation fun requestWait(timeoutMillis: Long): UsbRequest? {
        val reap = synchronized(RecoveryReplay) { RecoveryReplay.reaps.pollFirst() }
        return when (reap) {
            null, Reap.Timeout -> { Thread.sleep(timeoutMillis.coerceAtMost(20)); throw TimeoutException() }
            Reap.Null -> null
            Reap.Status -> synchronized(RecoveryReplay) {
                RecoveryReplay.statusBuffer!!.put(RecoveryReplay.statusNotification!!)
                RecoveryReplay.statusRequest
            }
            is Reap.Data -> synchronized(RecoveryReplay) {
                RecoveryReplay.dataBuffer!!.put(reap.bytes)
                RecoveryReplay.dataRequest
            }
        }
    }
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeoutMillis: Int): Int {
        RecoveryReplay.writes++
        RecoveryReplay.writeResults.pollFirst()?.let { return it }
        if (RecoveryReplay.refuseWrites) { Thread.sleep(timeoutMillis.toLong().coerceAtMost(5)); return -1 }
        RecoveryReplay.acceptedBlocks.add(buffer.copyOf(length))
        return length
    }
    @Implementation fun close() = Unit
}
