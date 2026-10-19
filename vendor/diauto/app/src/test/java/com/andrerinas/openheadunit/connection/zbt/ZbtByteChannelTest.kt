// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/zbt/ZbtByteChannelTest.kt
package com.andrerinas.openheadunit.connection.zbt

import com.andrerinas.openheadunit.utils.AppLog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * The channel driven over a scripted stream pair rather than a socket, so the parts that only fail
 * on real hardware — a frame arriving in pieces, a control frame landing in the middle of the byte
 * stream, a length that cannot be trusted — can be provoked on purpose.
 *
 * The demultiplexer is the piece with no second chance: it sits under the wireless handshake, and a
 * byte gained or lost there is not an error anyone would recognise as one. It would look like the
 * phone talking nonsense.
 */
class ZbtByteChannelTest {

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * A source that delivers exactly what a test tells it to, including the two things a
     * `ByteArrayInputStream` cannot express: a read that times out with nothing to give, and a
     * stream that ends.
     *
     * Running off the end of the script times out rather than ending, because "nothing more has
     * happened yet" is the daemon's normal state and a test that ran out of script should stall like
     * a quiet socket, not look like a closed one.
     */
    private class Source : InputStream() {
        private sealed class Step {
            class Data(val bytes: ByteArray) : Step()
            object Timeout : Step()
            object End : Step()
        }

        private val steps = ArrayDeque<Step>()
        private var current: ByteArray? = null
        private var offset = 0

        fun data(bytes: ByteArray) = apply { steps.addLast(Step.Data(bytes)) }
        fun timeout() = apply { steps.addLast(Step.Timeout) }
        fun end() = apply { steps.addLast(Step.End) }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val held = current
                if (held != null && offset < held.size) {
                    val take = minOf(len, held.size - offset)
                    System.arraycopy(held, offset, b, off, take)
                    offset += take
                    if (offset >= held.size) { current = null; offset = 0 }
                    return take
                }
                when (val step = steps.removeFirstOrNull() ?: Step.Timeout) {
                    is Step.Data -> { current = step.bytes; offset = 0 }
                    Step.Timeout -> throw SocketTimeoutException("scripted quiet")
                    Step.End -> return -1
                }
            }
        }
    }

    private lateinit var sink: ByteArrayOutputStream
    private lateinit var control: MutableList<Pair<Int, ByteArray>>
    private lateinit var rfcomm: MutableList<ByteArray>
    private var savedLogger: AppLog.Logger? = null

    @Before
    fun setUp() {
        // AppLog's default logger calls android.util.Log, which is not mocked in a JVM unit test and
        // throws. Swap it for one that keeps the lines: the channel's logging is not incidental —
        // reporters attach these logs and every round of this investigation has been read out of
        // them — so it is worth running the real calls rather than stubbing them out.
        savedLogger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        sink = ByteArrayOutputStream()
        control = mutableListOf()
        rfcomm = mutableListOf()
    }

    @After
    fun tearDown() {
        savedLogger?.let { AppLog.LOGGER = it }
    }

    private fun channel(source: Source) = ZbtByteChannel(
        source = source,
        sink = sink,
        onControlFrame = { id, body -> control.add(id to body) },
        onRfcommData = { bytes -> rfcomm.add(bytes) }
    )

    private fun rfcommFrame(vararg values: Int) =
        ZbtFraming.encodeFrame(values.map { it.toByte() }.toByteArray(), ZbtMessages.RFCOMM_DATA)

    private fun hex(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    /** Split the bytes the channel wrote back into (msgId, body) pairs. */
    private fun sentFrames(): List<Pair<Int, ByteArray>> {
        val all = sink.toByteArray()
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var at = 0
        while (at + ZbtFraming.HEADER_SIZE <= all.size) {
            val header = all.copyOfRange(at, at + ZbtFraming.HEADER_SIZE)
            assertTrue("frame at $at has our magic", ZbtFraming.hasValidMagic(header))
            val size = ZbtFraming.decodeBodySize(header)
            at += ZbtFraming.HEADER_SIZE
            out.add(ZbtFraming.decodeMsgId(header) to all.copyOfRange(at, at + size))
            at += size
        }
        assertEquals("no trailing partial frame", all.size, at)
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Receiving
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an rfcomm body reaches the input stream intact`() {
        val c = channel(Source().data(rfcommFrame(0xDE, 0xAD, 0xBE, 0xEF)))
        val got = ByteArray(4)
        assertEquals(4, c.input.read(got, 0, 4))
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), got)
        assertEquals(1, rfcomm.size)
    }

    @Test
    fun `a frame arriving in pieces is reassembled`() {
        // The header and the body are separate writes on this protocol, and neither is guaranteed to
        // land in one read. Splitting inside the header is the case that would desynchronise
        // everything after it if the reader trusted a single read.
        val frame = rfcommFrame(0x01, 0x02, 0x03, 0x04, 0x05)
        val source = Source()
            .data(frame.copyOfRange(0, 5))
            .timeout()
            .data(frame.copyOfRange(5, 17))
            .timeout()
            .data(frame.copyOfRange(17, frame.size))
        val c = channel(source)

        val got = ByteArray(5)
        assertEquals(5, c.input.read(got, 0, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), got)
    }

    @Test
    fun `control frames interleaved with data do not disturb the byte stream`() {
        // This is the demultiplexer's whole job. Link state arrives unprompted and at any moment,
        // including between two halves of a handshake message.
        val linkInfo = hex("08 84 02 10 01 18 01 20 00 2a 0c 30 30 30 32 35 42 44 33 35 30 42 43")
        val source = Source()
            .data(rfcommFrame(0xAA, 0xBB))
            .data(ZbtFraming.encodeFrame(linkInfo, ZbtMessages.LINK_INFO))
            .data(rfcommFrame(0xCC, 0xDD))
        val c = channel(source)

        val got = ByteArray(4)
        var have = 0
        while (have < 4) have += c.input.read(got, have, 4 - have)

        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), got)
        assertEquals(1, control.size)
        assertEquals(ZbtMessages.LINK_INFO, control[0].first)
        assertArrayEquals(linkInfo, control[0].second)
    }

    @Test
    fun `InitInfo hands over the module's real Bluetooth address`() {
        // Captured on 2026-08-12. The point of pulling this out at the channel level is that
        // BluetoothAdapter.getAddress() returns 02:00:00:00:00:00 to this app, and a masked address
        // is what makes a phone refuse wireless credentials.
        val initInfo = hex(
            "08 82 02 12 24 72 72 61 65 66 64 61 76 2d 73 70 64 61 2d 34 33 30 35 2d 39 38 34 62 " +
                "2d 61 65 74 64 36 39 37 32 32 30 30 31 1a 07 43 41 52 38 30 33 32 22 0c 30 30 30 " +
                "32 35 42 44 33 35 30 42 43"
        )
        val c = channel(Source().data(ZbtFraming.encodeFrame(initInfo, ZbtMessages.INIT_INFO)))

        assertNull(c.moduleMac)
        assertEquals(ZbtByteChannel.Pump.FRAME, c.pumpOnce(Long.MAX_VALUE))
        assertEquals("00025BD350BC", c.moduleMac)
    }

    @Test
    fun `a quiet daemon is not a finished one`() {
        val c = channel(Source().timeout())
        assertEquals(ZbtByteChannel.Pump.QUIET, c.pumpOnce(System.currentTimeMillis() + 60_000))
        assertFalse(c.isFinished)
    }

    @Test
    fun `a deadline already past expires without reading`() {
        val c = channel(Source().data(rfcommFrame(0x01)))
        assertEquals(ZbtByteChannel.Pump.EXPIRED, c.pumpOnce(System.currentTimeMillis() - 1))
        assertFalse("expiring is not a failure of the channel", c.isFinished)
        // and the frame is still there to be read afterwards
        assertEquals(ZbtByteChannel.Pump.FRAME, c.pumpOnce(Long.MAX_VALUE))
    }

    @Test
    fun `the caller can stop a pump that is waiting`() {
        val c = channel(Source().timeout())
        assertEquals(ZbtByteChannel.Pump.EXPIRED, c.pumpOnce(Long.MAX_VALUE, keepGoing = { false }))
        assertFalse(c.isFinished)
    }

    @Test
    fun `stopping part way through a header is a desync, not a timeout`() {
        // Giving up with nothing in hand leaves the stream on a frame boundary and the next attempt
        // starts cleanly. Giving up with five bytes of a header taken does not — reporting that as
        // an ordinary timeout would have the next pump read the tail of one header as the start of
        // the next, and every frame after it would be assembled out of the middle of others.
        val frame = rfcommFrame(0x01, 0x02)
        val c = channel(Source().data(frame.copyOfRange(0, 5)).timeout())
        var calls = 0

        assertEquals(ZbtByteChannel.Pump.ENDED, c.pumpOnce(Long.MAX_VALUE, keepGoing = { calls++ < 2 }))
        assertTrue(c.isFinished)
    }

    @Test
    fun `the channel finishes when the daemon closes the connection`() {
        val c = channel(Source().end())
        assertEquals(ZbtByteChannel.Pump.ENDED, c.pumpOnce(Long.MAX_VALUE))
        assertTrue(c.isFinished)
        assertEquals(-1, c.input.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `input returns end of stream rather than blocking once the channel is finished`() {
        val c = channel(Source().data(rfcommFrame(0x07)).end())
        val got = ByteArray(4)
        assertEquals(1, c.input.read(got, 0, 4))
        assertEquals(-1, c.input.read(got, 0, 4))
    }

    @Test
    fun `a wrong magic stops the channel instead of resynchronising`() {
        // The magic is the only field the peer validates and the only one that can tell us we have
        // lost sync. Carrying on past one would log frames assembled out of the middle of others.
        val bad = ZbtFraming.encodeFrame(byteArrayOf(1, 2), ZbtMessages.RFCOMM_DATA)
        bad[3] = 0x00
        val c = channel(Source().data(bad))
        assertEquals(ZbtByteChannel.Pump.ENDED, c.pumpOnce(Long.MAX_VALUE))
        assertTrue(c.isFinished)
    }

    @Test
    fun `a body larger than anything this protocol carries is refused rather than allocated`() {
        // decodeBodySize only rejects negatives, so a desynchronised stream can declare any positive
        // 31-bit number. Allocating it is how a probe turns a misread frame into an OutOfMemoryError.
        val header = ZbtFraming.encodeHeader(ZbtByteChannel.MAX_BODY_BYTES + 1, ZbtMessages.RFCOMM_DATA)
        val c = channel(Source().data(header))
        assertEquals(ZbtByteChannel.Pump.ENDED, c.pumpOnce(Long.MAX_VALUE))
        assertTrue(c.isFinished)
    }

    @Test
    fun `a body that stops arriving mid-frame ends the channel rather than being reported short`() {
        val frame = rfcommFrame(1, 2, 3, 4, 5, 6, 7, 8)
        val c = channel(Source().data(frame.copyOfRange(0, ZbtFraming.HEADER_SIZE + 3)).end())
        assertEquals(ZbtByteChannel.Pump.ENDED, c.pumpOnce(Long.MAX_VALUE))
        assertTrue(rfcomm.isEmpty())
    }

    @Test
    fun `an unread backlog stops the channel rather than dropping bytes`() {
        // Bytes arrive whether or not anyone reads them. Dropping the oldest would corrupt a stream
        // whose only job is to be exact, so the channel reports a stalled consumer instead.
        val big = ZbtFraming.encodeFrame(ByteArray(100_000), ZbtMessages.RFCOMM_DATA)
        val source = Source().data(big).data(big).data(big)
        val c = channel(source)

        assertEquals(ZbtByteChannel.Pump.FRAME, c.pumpOnce(Long.MAX_VALUE))
        assertEquals(ZbtByteChannel.Pump.FRAME, c.pumpOnce(Long.MAX_VALUE))
        assertEquals(100_000 * 2, c.input.available())
        assertEquals(ZbtByteChannel.Pump.ENDED, c.pumpOnce(Long.MAX_VALUE))
    }

    // ---------------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `RequestInit goes out byte-for-byte as the daemon has already accepted it`() {
        // These twenty bytes drew seven replies out of the vendor daemon on 2026-08-11 and again on
        // 2026-08-12. Everything else in this area is derived from a disassembly; this is the only
        // message a real peer has ever answered, so it is pinned whole rather than field by field.
        channel(Source()).sendRequestInit(ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
        assertArrayEquals(
            hex("00 00 ff ff 00 00 01 01 00 00 01 01 00 00 00 04 08 01 10 02"),
            sink.toByteArray()
        )
    }

    @Test
    fun `a write and a flush produce exactly one frame`() {
        // sendProtobuf writes a header and then a body before flushing. Two frames where the peer
        // expects one would split a handshake message across two RFCOMM writes.
        val c = channel(Source())
        c.output.write(byteArrayOf(0x11, 0x22))
        c.output.write(byteArrayOf(0x33))
        assertEquals("nothing goes out before the flush", 0, sink.size())
        c.output.flush()

        val frames = sentFrames()
        assertEquals(1, frames.size)
        assertEquals(ZbtMessages.RFCOMM_DATA, frames[0].first)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33), frames[0].second)
    }

    @Test
    fun `flushing an empty buffer sends nothing`() {
        val c = channel(Source())
        c.output.flush()
        assertEquals(0, sink.size())
    }

    @Test
    fun `a payload past the chunk cap is split across frames without losing a byte`() {
        val payload = ByteArray(2500) { (it % 251).toByte() }
        channel(Source()).sendRfcomm(payload)

        val frames = sentFrames()
        assertEquals(3, frames.size)
        assertEquals(ZbtByteChannel.MAX_WRITE_CHUNK, frames[0].second.size)
        assertEquals(ZbtByteChannel.MAX_WRITE_CHUNK, frames[1].second.size)
        assertEquals(2500 - 2 * ZbtByteChannel.MAX_WRITE_CHUNK, frames[2].second.size)
        assertArrayEquals(payload, frames.fold(ByteArray(0)) { acc, f -> acc + f.second })
        assertTrue(frames.all { it.first == ZbtMessages.RFCOMM_DATA })
    }

    @Test
    fun `requestReconnect sends the one message that acts on the module`() {
        channel(Source()).requestReconnect(ZbtMessages.ENABLE_TYPE_ANDROID_AUTO)
        val frames = sentFrames()
        assertEquals(1, frames.size)
        assertEquals(ZbtMessages.REQUEST_RECONN, frames[0].first)
        assertArrayEquals(hex("08 94 02 10 01 18 02"), frames[0].second)
    }

    @Test
    fun `requestLinkInfo sends a body the daemon will ignore, on the id it will not`() {
        channel(Source()).requestLinkInfo()
        val frames = sentFrames()
        assertEquals(ZbtMessages.LINK_INFO_REQUEST, frames[0].first)
        assertArrayEquals(hex("08 83 02"), frames[0].second)
    }

    // ---------------------------------------------------------------------------------------------
    // Delivery mode
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a watching caller gets its bytes without them piling up for a reader that never comes`() {
        // The probe consumes onRfcommData and never reads input. Buffering for it anyway is how a
        // healthy channel would hit the backlog cap and report a stalled consumer that does not
        // exist — which is exactly the failure this mode exists to prevent.
        val big = ZbtFraming.encodeFrame(ByteArray(100_000), ZbtMessages.RFCOMM_DATA)
        val c = ZbtByteChannel(
            source = Source().data(big).data(big).data(big),
            sink = sink,
            onRfcommData = { bytes -> rfcomm.add(bytes) },
            bufferRfcommData = false
        )

        repeat(3) { assertEquals(ZbtByteChannel.Pump.FRAME, c.pumpOnce(Long.MAX_VALUE)) }
        assertEquals("every frame still reached the watcher", 3, rfcomm.size)
        assertEquals("and none of it was kept", 0, c.input.available())
        assertFalse(c.isFinished)
    }

    @Test
    fun `a reading caller keeps its bytes`() {
        val c = channel(Source().data(rfcommFrame(0x41, 0x42)))
        assertEquals(ZbtByteChannel.Pump.FRAME, c.pumpOnce(Long.MAX_VALUE))
        assertEquals(2, c.input.available())
    }

    // ---------------------------------------------------------------------------------------------
    // Why the channel ended
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every way of ending names itself`() {
        // All of these reach a reader as the same thing — input returns -1 — so without a reason
        // recorded there is no way to tell the daemon hanging up from us misreading its stream. The
        // carrier decides whether to reopen on exactly that difference.
        val ended = channel(Source().end())
        ended.pumpOnce(Long.MAX_VALUE)
        assertTrue(ended.closeReason!!.contains("closed the connection"))

        val badMagic = ZbtFraming.encodeFrame(byteArrayOf(1), ZbtMessages.RFCOMM_DATA).also { it[3] = 0 }
        val desynced = channel(Source().data(badMagic))
        desynced.pumpOnce(Long.MAX_VALUE)
        assertTrue(desynced.closeReason!!.contains("magic"))

        val oversized = channel(
            Source().data(ZbtFraming.encodeHeader(ZbtByteChannel.MAX_BODY_BYTES + 1, ZbtMessages.RFCOMM_DATA))
        )
        oversized.pumpOnce(Long.MAX_VALUE)
        assertTrue(oversized.closeReason!!.contains("declared a body"))

        val big = ZbtFraming.encodeFrame(ByteArray(100_000), ZbtMessages.RFCOMM_DATA)
        val backlogged = channel(Source().data(big).data(big).data(big))
        repeat(3) { backlogged.pumpOnce(Long.MAX_VALUE) }
        assertTrue(backlogged.closeReason!!.contains("went unread"))
    }

    @Test
    fun `a channel that ended on its own keeps that reason when it is closed afterwards`() {
        // The carrier closes every channel it opened, including ones already finished. Overwriting
        // the real reason with "closed locally" at that point would lose the only diagnosis.
        val c = channel(Source().end())
        c.pumpOnce(Long.MAX_VALUE)
        c.close()
        assertTrue(c.closeReason!!.contains("closed the connection"))
    }

    @Test
    fun `a channel closed while healthy says so`() {
        val c = channel(Source())
        assertNull(c.closeReason)
        c.close()
        assertEquals("closed locally", c.closeReason)
    }

    // ---------------------------------------------------------------------------------------------
    // Two writers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `frames from two threads reach the socket whole`() {
        // The handshake sends from its own thread while a wake or a link-state poll goes out from
        // another. Two frames interleaved on the socket would desynchronise the daemon for the rest
        // of the session, and nothing downstream could recover or even diagnose it.
        //
        // The sink splits every write and yields in the middle, because a ByteArrayOutputStream
        // would not: its writes are atomic, so against a plain one this test would pass with no
        // lock at all. A real socket gives no such guarantee, and this is the behaviour being
        // relied on.
        val splitting = object : java.io.OutputStream() {
            override fun write(b: Int) = sink.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val half = len / 2
                sink.write(b, off, half)
                Thread.yield()
                sink.write(b, off + half, len - half)
            }
        }
        val c = ZbtByteChannel(source = Source(), sink = splitting)
        val payload = ByteArray(ZbtByteChannel.MAX_WRITE_CHUNK) { 0x5A }
        val rounds = 40

        val writer = Thread { repeat(rounds) { c.sendRfcomm(payload) } }
        val poller = Thread { repeat(rounds) { c.requestLinkInfo() } }
        writer.start(); poller.start()
        writer.join(10_000); poller.join(10_000)
        assertFalse("writer finished", writer.isAlive)
        assertFalse("poller finished", poller.isAlive)

        // sentFrames re-parses the whole stream and fails on a bad magic or a trailing partial
        // frame, so it only returns at all if every frame arrived intact and in one piece.
        val frames = sentFrames()
        assertEquals(rounds * 2, frames.size)
        assertEquals(rounds, frames.count { it.first == ZbtMessages.RFCOMM_DATA })
        assertEquals(rounds, frames.count { it.first == ZbtMessages.LINK_INFO_REQUEST })
        assertTrue(
            "every RFCOMM frame carries its whole payload",
            frames.filter { it.first == ZbtMessages.RFCOMM_DATA }.all { it.second.contentEquals(payload) }
        )
    }
}
