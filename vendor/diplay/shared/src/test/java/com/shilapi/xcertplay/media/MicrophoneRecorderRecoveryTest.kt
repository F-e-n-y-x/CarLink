package com.shilapi.xcertplay.media

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A recorder that does not start, or stops on a read error, is restarted and then replaced while the stream runs
 * (#1214, on the board of #647), with every attempt counted and reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneRecorderRecoveryTest {
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val built = CopyOnWriteArrayList<FakeRecorder>()
    private var uplink: MicrophoneUplink? = null

    @After fun tearDown() {
        uplink?.close()
    }

    @Test fun aRecorderThatDoesNotStartIsReportedAndReplacedBeforeAnyRead() {
        // The first recorder never records; the next one does.
        start(behaviour = { index -> if (index == 0) Behaviour(starts = { false }) else Behaviour() })

        awaitTrue { built.size == 2 && built[1].dataReads.get() > 5 }

        val failure = line("Microphone: failure ")
        assertTrue(failure, failure.contains("stage=RECORDING error=none code=${AudioRecord.RECORDSTATE_STOPPED}"))
        assertTrue(diagnostics.joinToString("\n"), diagnostics.none { it.startsWith("Microphone: start ") })
        assertEquals("the stopped recorder is never read", 0, built[0].readCalls.get())
        assertTrue(line("Microphone: recording restart failed").contains("attempt=1 reason=start recorder=same recordingState=1"))
        assertTrue(line("Microphone: recording restarted").contains("attempt=2 reason=start routedDeviceType=unknown recorder=new"))
        assertTrue(built[0].released)
        assertFalse(built[1].released)
    }

    @Test fun aFailedRestartAfterAReadErrorReplacesTheRecorderInsteadOfEndingTheCall() {
        // #1214: the first read fails with ERROR_INVALID_OPERATION and the same recorder will not start again.
        start(behaviour = { index ->
            if (index == 0) {
                Behaviour(starts = { attempt -> attempt == 1 }, reads = { read -> if (read == 1) AudioRecord.ERROR_INVALID_OPERATION else DATA })
            } else {
                Behaviour()
            }
        })

        awaitTrue { built.size == 2 && built[1].dataReads.get() > 5 }

        assertTrue(line("Microphone: start ").contains("source=VOICE_COMMUNICATION"))
        assertTrue(line("Microphone: failure ").contains("stage=READ error=none code=${AudioRecord.ERROR_INVALID_OPERATION}"))
        assertTrue(line("Microphone: recording restart failed").contains("attempt=1 reason=read-error recorder=same"))
        assertTrue(line("Microphone: recording restarted").contains("attempt=2 reason=read-error"))
        assertTrue(line("Microphone: recording restarted").contains("recorder=new platformEffects="))
        assertEquals(listOf(COMMUNICATION, COMMUNICATION), built.map { it.source })
        assertTrue(built[0].released)
        assertTrue(diagnostics.none { it.startsWith("Microphone: capture stopped") })
    }

    @Test fun aRestartThatWorksKeepsTheSameRecorder() {
        // #647: one restart of the same recorder was enough.
        start(behaviour = { Behaviour(reads = { read -> if (read == 1) AudioRecord.ERROR_INVALID_OPERATION else DATA }) })

        awaitTrue { built[0].dataReads.get() > 5 }

        assertEquals(1, built.size)
        assertTrue(line("Microphone: recording restarted").contains("attempt=1 reason=read-error routedDeviceType=unknown recorder=same"))
        assertEquals(2, built[0].startCalls.get())
    }

    @Test fun everyAttemptCountsWhetherOrNotItWorks() {
        // The first recorder records once and then fails; no restart or new recorder ever records.
        start(behaviour = { index ->
            if (index == 0) {
                Behaviour(starts = { attempt -> attempt == 1 }, reads = { AudioRecord.ERROR_INVALID_OPERATION })
            } else {
                Behaviour(starts = { false })
            }
        }, telephony = false)

        awaitTrue { diagnostics.any { it.startsWith("Microphone: capture stopped") } }
        awaitTrue { diagnostics.any { it.contains("ended=true") } }

        // One restart of the first recorder, then one new recorder per pause; none of them recorded again.
        assertEquals(1 + BACKOFF.size, built.size)
        assertEquals(BACKOFF.size + 1, diagnostics.count { it.startsWith("Microphone: recording restart failed") })
        assertTrue(line("Microphone: capture stopped").contains("reason=recording-failed attempts=${1 + BACKOFF.size}"))
        assertTrue("every recorder is released", built.all { it.released })
    }

    @Test fun aStreamGivesUpOnceItsAttemptBudgetIsSpentEvenWhenRestartsWork() {
        // Each restart "works" but the next read fails again; successes used to be the only thing counted.
        start(behaviour = { Behaviour(reads = { AudioRecord.ERROR_INVALID_OPERATION }) }, telephony = false)

        awaitTrue { diagnostics.any { it.contains("ended=true") } }

        val restarts = diagnostics.filter { it.startsWith("Microphone: recording restarted") }
        assertEquals(diagnostics.joinToString("\n"), MAX_ATTEMPTS, restarts.size)
        assertTrue(line("Microphone: capture stopped").contains("attempts=$MAX_ATTEMPTS"))
        assertEquals(1, built.size)
    }

    @Test fun aDeadRecorderIsReplacedWithoutRestartingIt() {
        start(behaviour = { index ->
            if (index == 0) Behaviour(reads = { AudioRecord.ERROR_DEAD_OBJECT }) else Behaviour()
        }, telephony = false)

        awaitTrue { built.size == 2 && built[1].dataReads.get() > 5 }

        assertEquals("no restart of a dead recorder", 1, built[0].startCalls.get())
        assertTrue(line("Microphone: recording restarted").contains("attempt=1 reason=read-error routedDeviceType=unknown recorder=new"))
        assertTrue(diagnostics.none { it.startsWith("Microphone: recording restart failed") })
    }

    @Test fun aCallFallsBackToVoiceRecognitionAfterTwoFailedCommunicationRecorders() {
        start(behaviour = { index ->
            when {
                index == 0 -> Behaviour(starts = { attempt -> attempt == 1 }, reads = { AudioRecord.ERROR_INVALID_OPERATION })
                built[index].source == COMMUNICATION -> Behaviour(starts = { false })
                else -> Behaviour()
            }
        })

        awaitTrue { built.size == 4 && built[3].dataReads.get() > 5 }

        assertEquals(listOf(COMMUNICATION, COMMUNICATION, COMMUNICATION, RECOGNITION), built.map { it.source })
        val fallback = line("Microphone: source fallback")
        assertTrue(fallback, fallback.contains("type=telephony from=VOICE_COMMUNICATION to=VOICE_RECOGNITION failedRecorders=2"))
        assertTrue(fallback, fallback.contains("defaultPlatformAec=none diplayEchoCanceller=off"))
        val restarted = line("Microphone: recording restarted")
        assertTrue(restarted, restarted.contains("type=telephony source=VOICE_RECOGNITION"))
        assertTrue(restarted, restarted.contains("attempt=4 reason=read-error"))
    }

    @Test fun aGeelyCallKeepsVoiceCommunication() {
        start(behaviour = { index ->
            if (index == 0) Behaviour(starts = { attempt -> attempt == 1 }, reads = { AudioRecord.ERROR_INVALID_OPERATION })
            else Behaviour(starts = { false })
        }, geely = true)

        awaitTrue { diagnostics.any { it.startsWith("Microphone: capture stopped") } }

        assertTrue(built.all { it.source == COMMUNICATION })
        assertTrue(diagnostics.none { it.startsWith("Microphone: source fallback") })
    }

    @Test fun siriNeverChangesSourceWhenItsRecorderFails() {
        start(behaviour = { Behaviour(starts = { false }) }, type = "speechrecognition")

        awaitTrue { diagnostics.any { it.startsWith("Microphone: capture stopped") } }

        assertTrue(built.all { it.source == RECOGNITION })
        assertTrue(diagnostics.none { it.startsWith("Microphone: source fallback") })
    }

    @Test fun closingDuringAPauseStopsRecoveryPromptlyAndReleasesTheRecorder() {
        start(
            behaviour = { Behaviour(starts = { attempt -> attempt == 1 }, reads = { AudioRecord.ERROR_INVALID_OPERATION }) },
            backoff = listOf(60_000L),
        )
        awaitTrue { diagnostics.any { it.startsWith("Microphone: recording restart failed") } }

        val began = System.nanoTime()
        requireNotNull(uplink).close()
        val closeMillis = (System.nanoTime() - began) / 1_000_000

        assertTrue("close took ${closeMillis}ms", closeMillis < 400)
        assertEquals("no recorder is built after close", 1, built.size)
        assertTrue(built[0].released)
        assertTrue(diagnostics.none { it.startsWith("Microphone: capture stopped") })
    }

    private fun start(
        behaviour: (Int) -> Behaviour,
        telephony: Boolean = true,
        type: String = if (telephony) "telephony" else "other",
        geely: Boolean = false,
        backoff: List<Long> = BACKOFF,
    ) {
        val factory = MicrophoneRecorderFactory { source, _, _, _ ->
            val index = built.size
            val recorder = FakeRecorder(source, index)
            built += recorder
            recorder.behaviour = behaviour(index)
            recorder
        }
        val next = MicrophoneUplink(
            config(type),
            onDiagnostic = diagnostics::add,
            recorderFactory = factory,
            rebuildBackoffMillis = backoff,
            geelyHeadUnit = { geely },
        )
        uplink = next
        assertTrue(next.start())
    }

    private fun line(prefix: String): String =
        diagnostics.firstOrNull { it.startsWith(prefix) } ?: throw AssertionError("no \"$prefix\" in:\n${diagnostics.joinToString("\n")}")

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out; diagnostics:\n${diagnostics.joinToString("\n")}")
            Thread.sleep(5)
        }
    }

    private fun config(type: String) = MicrophoneConfig(
        audioType = type,
        sampleRate = 16_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 9,
        key = ByteArray(32),
    )

    /** [starts] gets the 1-based startRecording call; [reads] the 1-based read while recording. */
    class Behaviour(val starts: (Int) -> Boolean = { true }, val reads: (Int) -> Int = { DATA })

    /** Behaves like AudioRecord: a refused start leaves it stopped, and reading a stopped recorder fails at once. */
    class FakeRecorder(val source: Int, index: Int) : MicrophoneRecorder {
        @Volatile var behaviour = Behaviour()
        val startCalls = AtomicInteger()
        val readCalls = AtomicInteger()
        val dataReads = AtomicInteger()
        private val recordingReads = AtomicInteger()
        @Volatile var released = false
            private set
        @Volatile private var recording = false

        override val state = AudioRecord.STATE_INITIALIZED
        override val recordingState get() = if (recording) AudioRecord.RECORDSTATE_RECORDING else AudioRecord.RECORDSTATE_STOPPED
        override val audioSessionId = 1_000 + index

        override fun routedDeviceType(): Int? = null

        override fun startRecording() {
            check(!released) { "released" }
            recording = behaviour.starts(startCalls.incrementAndGet())
        }

        override fun stop() {
            recording = false
        }

        override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
            readCalls.incrementAndGet()
            if (released || !recording) return AudioRecord.ERROR_INVALID_OPERATION
            Thread.sleep(2)
            val result = behaviour.reads(recordingReads.incrementAndGet())
            if (result > 0) {
                dataReads.incrementAndGet()
                return minOf(result, size)
            }
            return result
        }

        override fun timestamp(into: AudioTimestamp) = false

        override fun release() {
            released = true
            recording = false
        }
    }

    private companion object {
        const val DATA = 640
        const val MAX_ATTEMPTS = 8
        val BACKOFF = listOf(1L, 1L, 1L, 1L)
        const val COMMUNICATION = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        const val RECOGNITION = MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
}
