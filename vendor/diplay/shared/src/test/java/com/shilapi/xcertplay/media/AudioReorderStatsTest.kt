package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.util.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The audio renderer queues RTP packets in sequence order and reports what it reordered or dropped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioReorderStatsTest {
    private val reports = mutableListOf<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
        .newInstance(AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 102, "media"),
            false, false, 0, 0, AudioFocusCoordinator(null, false, false),
            0, 1000, { message: String -> reports.add(message) }, 0L, false, null, false)

    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val queue get() = field("queue").get(renderer) as Queue<Any>

    private fun submit(sequence: Int) = type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(renderer, ByteArray(12 + 8).apply { this[2] = (sequence shr 8).toByte(); this[3] = sequence.toByte() }, sequence * 1024)

    private fun sequenceOf(packet: Any): Int =
        RtpReorderQueue.rtpSequence(packet.javaClass.getDeclaredField("rtp").apply { isAccessible = true }.get(packet) as ByteArray)

    @Test fun lateAndDuplicatePacketsShowInTheStats() {
        field("started").set(renderer, true)
        listOf(1, 3, 2, 3).forEach(::submit)
        assertEquals(listOf(1, 2, 3), queue.map(::sequenceOf))
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(reports.toString(), reports.any { it.startsWith("audio stats") && it.contains(" dropped=0 reordered=1 lateDropped=1 ") })
        assertTrue(reports.toString(), reports.any {
            it.startsWith("Audio: decoder stats") && it.contains(" reorderedTotal=1 lateDroppedTotal=1 ")
        })
    }

    @Test fun aNewStreamOnTheSameRendererIsNotDroppedAsLate() {
        field("started").set(renderer, true)
        (100..105).forEach(::submit)
        repeat(6) { queue.poll() }
        submit(103)
        assertTrue(queue.isEmpty())
        // started is already set: start() only restarts the sequence and does not launch the worker.
        type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(renderer)
        (101..102).forEach(::submit)
        assertEquals(listOf(101, 102), queue.map(::sequenceOf))
    }
}
