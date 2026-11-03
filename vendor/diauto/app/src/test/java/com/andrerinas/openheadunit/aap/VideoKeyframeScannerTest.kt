package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoKeyframeScannerTest {

    /** Four-byte start codes, with a second header byte for HEVC and dummy payload. */
    private fun accessUnit(vararg nals: Int): ByteArray {
        val out = ArrayList<Byte>()
        for (header in nals) {
            out.add(0); out.add(0); out.add(0); out.add(1)
            out.add(header.toByte())
            out.add(0x01)
            repeat(2) { out.add(0x42) }
        }
        return out.toByteArray()
    }

    private fun scan(data: ByteArray, isHevc: Boolean) =
        VideoKeyframeScanner.containsKeyframe(data, 0, data.size, isHevc)

    // --- H.264 -----------------------------------------------------------------------------

    @Test
    fun `an h264 idr slice is a keyframe`() {
        assertTrue(scan(accessUnit(0x65), isHevc = false)) // nal_ref_idc=3, type 5
    }

    @Test
    fun `an h264 idr behind its parameter sets is still found`() {
        // What the phone actually sends: SPS (7), PPS (8), then the IDR slice.
        assertTrue(scan(accessUnit(0x67, 0x68, 0x65), isHevc = false))
    }

    @Test
    fun `an h264 non-idr slice is not a keyframe`() {
        assertFalse(scan(accessUnit(0x41), isHevc = false)) // type 1
    }

    @Test
    fun `parameter sets alone are not a keyframe`() {
        // A config-only message carries no picture, so it repairs nothing on its own.
        assertFalse(scan(accessUnit(0x67, 0x68), isHevc = false))
    }

    // --- H.265 -----------------------------------------------------------------------------

    @Test
    fun `hevc irap types are keyframes across the whole range`() {
        for (type in 16..21) {
            assertTrue("HEVC NAL type $type", scan(accessUnit(type shl 1), isHevc = true))
        }
    }

    @Test
    fun `an hevc trailing picture is not a keyframe`() {
        assertFalse(scan(accessUnit(0x02), isHevc = true)) // TRAIL_R, type 1
    }

    @Test
    fun `an hevc idr behind vps sps pps is still found`() {
        assertTrue(scan(accessUnit(32 shl 1, 33 shl 1, 34 shl 1, 19 shl 1), isHevc = true))
    }

    // --- The mismatch that froze a session once --------------------------------------------

    @Test
    fun `reading an h264 idr as hevc can go either way, which is why the pinned type is passed`() {
        // The header byte carries nal_ref_idc as well as the type, and the two codecs read different
        // bits of it. 0x65 (the IDR the phone actually sends, nal_ref_idc=3) reads as HEVC type 50
        // and is correctly rejected - but 0x25, the same IDR at nal_ref_idc=1, reads as HEVC type 18
        // and would be accepted. So a wrong codec type gives a wrong answer in both directions.
        // That is the failure 66e59b2c hit by reading headers against the user's preference; the
        // caller passes the decoder's pinned type instead. Pinned here so the reason stays visible.
        assertTrue(scan(accessUnit(0x65), isHevc = false))
        assertFalse(scan(accessUnit(0x65), isHevc = true))

        assertTrue(scan(accessUnit(0x25), isHevc = false))
        assertTrue(scan(accessUnit(0x25), isHevc = true))
    }

    // --- Framing -----------------------------------------------------------------------------

    @Test
    fun `three byte start codes are recognised`() {
        val data = byteArrayOf(0, 0, 1, 0x65, 0x42, 0x42)
        assertTrue(VideoKeyframeScanner.containsKeyframe(data, 0, data.size, isHevc = false))
    }

    @Test
    fun `an offset into a larger buffer is honoured`() {
        val prefix = byteArrayOf(0x11, 0x22)
        val data = prefix + accessUnit(0x65)
        assertTrue(
            VideoKeyframeScanner.containsKeyframe(data, prefix.size, data.size - prefix.size, isHevc = false)
        )
        // The same bytes below the offset must not be reachable.
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, 0, prefix.size, isHevc = false))
    }

    @Test
    fun `a truncated or empty access unit is not a keyframe`() {
        assertFalse(VideoKeyframeScanner.containsKeyframe(ByteArray(0), 0, 0, isHevc = false))
        assertFalse(VideoKeyframeScanner.containsKeyframe(byteArrayOf(0, 0, 1), 0, 3, isHevc = false))
    }

    @Test
    fun `an idr is found after many metadata nals and a large payload`() {
        val metadata = accessUnit(*IntArray(12) { 0x06 })
        val data = metadata + ByteArray(4096) { 0x42 } + accessUnit(0x65)
        assertTrue(scan(data, isHevc = false))
    }

    @Test
    fun `a non-idr nal does not hide a later idr`() {
        assertTrue(scan(accessUnit(0x41, 0x65), isHevc = false))
        assertTrue(scan(accessUnit(0x02, 19 shl 1), isHevc = true))
    }

    @Test
    fun `start codes are found at every skip alignment`() {
        for (padding in 0..5) {
            for (prefix in arrayOf(byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 0, 1))) {
                val leading = ByteArray(padding) { 0x42 }
                assertTrue(scan(leading + prefix + byteArrayOf(0x65), isHevc = false))
                assertTrue(scan(leading + prefix + byteArrayOf(0x26, 0x01), isHevc = true))
            }
        }
    }

    @Test
    fun `mixed prefixes and emulation prevention bytes do not hide an idr`() {
        val data = byteArrayOf(0, 0, 1, 0x41, 0, 0, 3, 1, 0x65, 0, 0, 0, 1, 0x65)
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, 0, 9, isHevc = false))
        assertTrue(scan(data, isHevc = false))
    }

    @Test
    fun `a prefix or header outside the requested view is not read`() {
        val avc = byteArrayOf(0, 0, 1, 0x65)
        assertFalse(VideoKeyframeScanner.containsKeyframe(avc, 1, 3, isHevc = false))
        assertFalse(VideoKeyframeScanner.containsKeyframe(avc, 0, 3, isHevc = false))
        assertTrue(scan(avc, isHevc = false))

        val hevc = byteArrayOf(0, 0, 1, 0x26, 0x01)
        assertFalse(VideoKeyframeScanner.containsKeyframe(hevc, 0, 4, isHevc = true))
        assertTrue(scan(hevc, isHevc = true))
        assertFalse(scan(hevc.copyOf(4), isHevc = true))
    }

    @Test
    fun `oversized views clip to the array without integer overflow`() {
        val data = byteArrayOf(0x42) + accessUnit(0x65)
        assertTrue(VideoKeyframeScanner.containsKeyframe(data, 1, Int.MAX_VALUE, isHevc = false))
        assertFalse(
            VideoKeyframeScanner.containsKeyframe(byteArrayOf(0, 0, 1), 0, Int.MAX_VALUE, isHevc = false)
        )
    }

    @Test
    fun `invalid or empty views contain no keyframe`() {
        val data = accessUnit(0x65)
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, -1, data.size, isHevc = false))
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, data.size, 4, isHevc = false))
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, Int.MAX_VALUE, 4, isHevc = false))
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, 0, -1, isHevc = false))
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, 0, 0, isHevc = false))
    }

    @Test
    fun `invalid nal headers are not keyframes`() {
        // An AVC IDR must have forbidden_zero_bit=0 and nonzero nal_ref_idc.
        assertFalse(scan(accessUnit(0xE5), isHevc = false))
        assertFalse(scan(accessUnit(0x05), isHevc = false))
        // HEVC requires forbidden_zero_bit=0 and nonzero nuh_temporal_id_plus1.
        assertFalse(scan(byteArrayOf(0, 0, 1, 0xA6.toByte(), 0x01), isHevc = true))
        assertFalse(scan(byteArrayOf(0, 0, 1, 0x26, 0x00), isHevc = true))
        assertTrue(scan(accessUnit(0xE5, 0x65), isHevc = false))
        assertTrue(
            scan(byteArrayOf(0, 0, 1, 0x26, 0x00) + accessUnit(0x26), isHevc = true)
        )
    }
}
