package com.shilapi.xcertplay.airplay

import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedAudioStreamTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, BufferedAudioStream.STREAM_TYPE, "media")

    @Test
    fun theOfferIsMadeOnlyWhenEnabled() {
        val off = AirPlayInfoPlist.build(config)
        assertFalse(off.containsKey("mainBufferedInfo"))
        assertFalse((off["audioFormats"] as List<*>).any { (it as Map<*, *>)["type"] == 103 })
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(config, listOf("mainBuffered", "iAPChannel")))

        val on = config.copy(mainBufferedAudio = true)
        val info = AirPlayInfoPlist.build(on)
        assertEquals(emptyMap<String, Any?>(), info["mainBufferedInfo"])
        val entry = (info["audioFormats"] as List<*>).single { (it as Map<*, *>)["type"] == 103 } as Map<*, *>
        assertEquals("media", entry["audioType"])
        assertEquals(0x800000, entry["audioOutputFormats"]) // AAC-LC 48 kHz stereo
        assertTrue(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(on, listOf("mainBuffered", "iAPChannel")))
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(on, listOf("iAPChannel")))
    }

    @Test
    fun framesOpenWithTheStreamKeyAndKeepTheirRtpHeader() {
        val payload = byteArrayOf(0x21, 0x6c, 0x4f, 0x55, 0x10)
        val body = seal(sequence = 7, timestamp = 0xfffffc00L, payload = payload)
        val frame = BufferedAudioStream.open(key, body)!!
        assertEquals(7, frame.sequence)
        assertEquals(0xfffffc00L, frame.timestamp)
        assertArrayEquals(payload, frame.rtp.copyOfRange(12, frame.rtp.size))
        assertNull(BufferedAudioStream.open(ByteArray(32), body))
        assertNull(BufferedAudioStream.open(key, body.copyOf(20)))
    }

    @Test
    fun theAnchorUsesThe1970EpochAndA64BitFraction() {
        // NTP seconds 2208988800 + 131959 (the iPhone's clock) and half a second.
        val ntp = BigInteger.valueOf(2_208_988_800L + 131_959L).shiftLeft(32).or(BigInteger.valueOf(0x8000_0000L))
        val anchor = BufferedAudioStream.anchorPlist(0x1_0000_0010L, ntp, 1)
        assertEquals(0x10L, anchor["rtpTime"])
        assertEquals(131_959L, anchor["networkTimeSecs"])
        assertEquals(BigInteger.ONE.shiftLeft(63), anchor["networkTimeFrac"])
        assertEquals(1, anchor["rate"])
    }

    @Test
    fun timestampsCompareAcrossTheWrap() {
        assertTrue(BufferedAudioStream.before(0xffff_ff00L, 0x100L))
        assertFalse(BufferedAudioStream.before(0x100L, 0xffff_ff00L))
        assertTrue(BufferedAudioStream.before(1_000L, 2_048L))
        assertFalse(BufferedAudioStream.before(2_048L, 2_048L))
    }

    @Test
    fun setRateStartsPlaybackFromItsTimeAndPausesAndFlushesStopTheOutput() {
        val sink = RecordingSink()
        val stream = BufferedAudioStream(key, format, sink)
        try {
            stream.start()
            assertNull(stream.anchor())
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                for (i in 0 until 4) {
                    val body = seal(sequence = i, timestamp = 1_000L + i * 1_024L, payload = byteArrayOf(0x21, i.toByte()))
                    out.writeShort(body.size + 2)
                    out.write(body)
                }
                out.flush()
                // Nothing plays before SETRATE.
                Thread.sleep(150)
                assertTrue(sink.rtp.isEmpty())

                val now = BigInteger.valueOf(2_208_988_800L + 100L).shiftLeft(32)
                val anchor = stream.setRate(rtpTime = 2_024L, newRate = 1, nowNtp = now)!!
                assertEquals(2_024L, anchor["rtpTime"])
                assertEquals(100L, anchor["networkTimeSecs"])
                assertEquals(anchor, stream.anchor())
                waitFor { sink.rtp.size == 3 }
                // The frame before rtpTime was dropped; playback starts at it.
                assertEquals(listOf(2_024L, 3_048L, 4_072L), sink.rtp.map { it.toLong() and 0xffff_ffffL })
                assertEquals(1, sink.started.size)

                val paused = stream.setRate(rtpTime = null, newRate = 0, nowNtp = now)!!
                assertEquals(0, paused["rate"])
                assertTrue(sink.stopped >= 1)
                val feedback = stream.feedback(now, 42L)
                assertEquals(103, feedback["type"])
                assertEquals(paused["rtpTime"], feedback["sampleTime"])

                val stopsBeforeFlush = sink.stopped
                stream.flush(untilTimestamp = 9_000L)
                assertTrue(sink.stopped >= stopsBeforeFlush)
            }
        } finally {
            stream.close()
        }
    }

    private fun seal(sequence: Int, timestamp: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60
        header[2] = (sequence shr 8).toByte(); header[3] = sequence.toByte()
        for (i in 0 until 4) header[4 + i] = (timestamp shr (24 - 8 * i)).toByte()
        val nonceTail = ByteArray(8) { (sequence + it).toByte() }
        val nonce = ByteArray(12).also { nonceTail.copyInto(it, 4) }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12))
        return header + sealed + nonceTail
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not met in time", condition())
    }

    private class RecordingSink : MediaSink {
        val started = CopyOnWriteArrayList<Int>()
        val rtp = CopyOnWriteArrayList<Int>()
        @Volatile var stopped = 0
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { started += firstSample }
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { this.rtp += sample }
        override fun onAudioStopped(id: AudioStreamId) { stopped++ }
    }
}
