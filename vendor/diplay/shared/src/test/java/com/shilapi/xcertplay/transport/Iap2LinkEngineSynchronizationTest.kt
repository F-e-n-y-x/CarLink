package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1220: the iPhone re-sent its SYN+ACK once a second because it never saw DiPlay's ACK.
 * The wired link advertises zero retransmissions and acknowledgements, so the engine must answer
 * each repeat itself or identification never starts.
 */
class Iap2LinkEngineSynchronizationTest {
    @Test
    fun reacknowledgesARepeatedSynAckAfterTheLinkIsUp() {
        val engine = synchronizedWiredEngine()

        engine.feed(peerSynAck(), 1_000)

        assertArrayEquals(acknowledgement(PEER_SYN_SEQUENCE), engine.takeOutput())
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
        assertTrue(engine.writable())
        assertNull(engine.pollEvent())

        engine.feed(peerSynAck(), 2_000)

        assertArrayEquals(acknowledgement(PEER_SYN_SEQUENCE), engine.takeOutput())
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
        assertNull(engine.pollEvent())
    }

    @Test
    fun dataStillFlowsAfterARepeatedSynAck() {
        val engine = synchronizedWiredEngine()
        engine.feed(peerSynAck(), 1_000)
        engine.takeOutput()

        val startIdentification = byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1d, 0x00)
        engine.feed(frame(CONTROL_ACK, PEER_SYN_SEQUENCE + 1, LOCAL_SEQUENCE, CONTROL_SESSION, startIdentification), 1_010)

        val control = engine.pollEvent() as Iap2LinkEngine.Event.Control
        assertArrayEquals(startIdentification, control.bytes)
        assertNull(engine.pollEvent())

        val identification = byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1d, 0x01)
        engine.sendControl(identification, 1_020)
        assertArrayEquals(
            frame(CONTROL_ACK, LOCAL_SEQUENCE + 1, PEER_SYN_SEQUENCE + 1, CONTROL_SESSION, identification),
            engine.takeOutput(),
        )

        val accepted = byteArrayOf(0x40, 0x40, 0x00, 0x06, 0x1d, 0x02)
        engine.feed(frame(CONTROL_ACK, PEER_SYN_SEQUENCE + 2, LOCAL_SEQUENCE + 1, CONTROL_SESSION, accepted), 1_030)
        assertArrayEquals(accepted, (engine.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
    }

    @Test
    fun ignoresARepeatedSynAckOnceDataHasArrived() {
        val engine = synchronizedWiredEngine()
        engine.feed(frame(CONTROL_ACK, PEER_SYN_SEQUENCE + 1, LOCAL_SEQUENCE, CONTROL_SESSION, byteArrayOf(1)), 1_000)
        while (engine.pollEvent() != null) Unit
        engine.takeOutput()

        engine.feed(peerSynAck(), 2_000)

        assertEquals(0, engine.takeOutput().size)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
    }

    @Test
    fun ignoresASynAckThatDoesNotRepeatThePeerSynchronization() {
        val engine = synchronizedWiredEngine()

        engine.feed(peerSynAck(sequence = PEER_SYN_SEQUENCE + 7), 1_000)
        engine.feed(
            peerSynAck(synchronization = PEER_SYNCHRONIZATION.copy(maxOutgoing = PEER_SYNCHRONIZATION.maxOutgoing + 1)),
            1_100,
        )

        assertEquals(0, engine.takeOutput().size)
        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
    }

    /** A wired engine after the first SYN+ACK, with its output and events drained. */
    private fun synchronizedWiredEngine(): Iap2LinkEngine {
        val engine = Iap2LinkEngine(WIRED_CONFIG)
        engine.start(wiredInitiator = true, nowMillis = 0)
        engine.takeOutput()

        engine.feed(peerSynAck(), 10)

        assertEquals(Iap2LinkEngine.State.NORMAL, engine.state())
        assertArrayEquals(acknowledgement(PEER_SYN_SEQUENCE), engine.takeOutput())
        while (engine.pollEvent() != null) Unit
        return engine
    }

    private fun peerSynAck(
        sequence: Int = PEER_SYN_SEQUENCE,
        synchronization: Iap2LinkEngine.SynchronizationPayload = PEER_SYNCHRONIZATION,
    ): ByteArray = frame(CONTROL_SYN or CONTROL_ACK, sequence, LOCAL_SEQUENCE, 0, synchronization.encode())

    private fun acknowledgement(acknowledgement: Int): ByteArray =
        frame(CONTROL_ACK, LOCAL_SEQUENCE, acknowledgement, 0, null)

    private fun frame(
        control: Int,
        sequence: Int,
        acknowledgement: Int,
        sessionId: Int,
        payload: ByteArray?,
    ): ByteArray {
        val length = if (payload == null) HEADER_BYTES else HEADER_BYTES + payload.size + 1
        val header = byteArrayOf(
            0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
            control.toByte(), sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(), 0,
        )
        header[8] = checksum(header.copyOf(HEADER_BYTES - 1)).toByte()
        return if (payload == null) header else header + payload + checksum(payload).toByte()
    }

    private fun checksum(bytes: ByteArray): Int =
        (-bytes.sumOf { it.toInt() and 0xff }) and 0xff

    private companion object {
        const val HEADER_BYTES = 9
        const val CONTROL_SYN = 0x80
        const val CONTROL_ACK = 0x40
        const val CONTROL_SESSION = Iap2LinkEngine.CONTROL_SESSION_ID

        // Sequence numbers from the #1220 report: DiPlay's SYN is 99, the iPhone's SYN+ACK is 144.
        const val LOCAL_SEQUENCE = 99
        const val PEER_SYN_SEQUENCE = 144

        // Iap2LinkChannel's wired configuration.
        val WIRED_CONFIG = Iap2LinkConfig(
            maxOutgoing = 4,
            controlSessionVersion = 2,
            zeroAcknowledgements = true,
        )

        // The iPhone echoes DiPlay's zero-acknowledgement parameters (a 29-byte SYN+ACK).
        val PEER_SYNCHRONIZATION = Iap2LinkEngine.SynchronizationPayload(
            maxOutgoing = 4,
            maxLength = Iap2LinkEngine.MAX_WIRE_FRAME_BYTES,
            retransmissionTimeoutMillis = 0,
            acknowledgementTimeoutMillis = 0,
            maxRetransmissions = 0,
            maxAcknowledgements = 0,
            sessions = listOf(
                Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.CONTROL_SESSION_ID, 0, 2),
                Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.EA_SESSION_ID, 2, 1),
                Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.FILE_TRANSFER_SESSION_ID, 1, 2),
            ),
        )
    }
}
