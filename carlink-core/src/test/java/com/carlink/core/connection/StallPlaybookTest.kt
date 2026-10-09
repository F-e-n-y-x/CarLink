package com.carlink.app.connection

import com.carlink.app.core.Engine
import com.carlink.app.core.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stall playbook and the adaptive deadline rule (pure). */
class StallPlaybookTest {
    private val wireless = Transport.WIRELESS
    private val usb = Transport.USB

    @Test fun everyPhaseEndsInAFailureWithAReason() {
        for (engine in Engine.entries) for (transport in Transport.entries) {
            for (stage in ProgressStage.entries.map<ProgressStage, ProgressStage?> { it } + null) {
                val actions = StallPlaybook.actions(engine, transport, stage)
                assertTrue("$engine $transport $stage", actions.last() == RecoveryAction.GIVE_UP || actions.last() == RecoveryAction.ASK_REPLUG)
                val (_, detail) = StallPlaybook.failure(engine, transport, stage, 20_000)
                assertTrue(detail.isNotBlank())
            }
        }
    }

    @Test fun thePlaybookTable() {
        assertEquals(listOf(RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.GIVE_UP),
            StallPlaybook.actions(Engine.ANDROID_AUTO, wireless, ProgressStage.WAITING_FOR_PHONE))
        assertEquals(listOf(RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.NUDGE, RecoveryAction.ASK_REPLUG),
            StallPlaybook.actions(Engine.CARPLAY, usb, ProgressStage.PHONE_NOT_ANSWERING))
        assertEquals(listOf(RecoveryAction.NUDGE, RecoveryAction.RESWITCH_USB, RecoveryAction.ASK_REPLUG),
            StallPlaybook.actions(Engine.ANDROID_AUTO, usb, ProgressStage.HANDSHAKE))
        assertEquals(listOf(RecoveryAction.NUDGE, RecoveryAction.RETRY_STEP, RecoveryAction.ASK_REPLUG),
            StallPlaybook.actions(Engine.CARPLAY, usb, ProgressStage.HANDSHAKE))
        assertEquals(listOf(RecoveryAction.RESET_WIFI_DIRECT, RecoveryAction.NEXT_PATH, RecoveryAction.GIVE_UP),
            StallPlaybook.actions(Engine.CARPLAY, wireless, ProgressStage.JOINING_WIFI))
        assertEquals(listOf(RecoveryAction.RETRY_STEP, RecoveryAction.NEXT_PATH, RecoveryAction.GIVE_UP),
            StallPlaybook.actions(Engine.CARPLAY, wireless, ProgressStage.STARTING_PROJECTION))
        assertEquals(listOf(RecoveryAction.NEXT_PATH, RecoveryAction.GIVE_UP), StallPlaybook.actions(Engine.CARPLAY, wireless, null))
        assertEquals(listOf(RecoveryAction.ASK_REPLUG), StallPlaybook.actions(Engine.ANDROID_AUTO, usb, null))
    }

    @Test fun nextPathIsSkippedWhenThereIsNone() {
        val actions = StallPlaybook.actions(Engine.CARPLAY, wireless, ProgressStage.STARTING_PROJECTION)
        assertEquals(RecoveryAction.RETRY_STEP to 1, StallPlaybook.next(actions, 0, hasNextPath = false))
        assertEquals(RecoveryAction.GIVE_UP to 3, StallPlaybook.next(actions, 1, hasNextPath = false))
        assertEquals(RecoveryAction.NEXT_PATH to 2, StallPlaybook.next(actions, 1, hasNextPath = true))
        assertEquals("past the end is a failure", RecoveryAction.GIVE_UP to 6, StallPlaybook.next(actions, 5, hasNextPath = true))
    }

    @Test fun failuresNameThePhaseAndTheFix() {
        assertEquals(FailureReason.PHONE_DID_NOT_ANSWER, StallPlaybook.failure(Engine.CARPLAY, wireless, ProgressStage.WAITING_FOR_PHONE, 30_000).first)
        assertEquals(FailureReason.USB, StallPlaybook.failure(Engine.ANDROID_AUTO, usb, ProgressStage.WAITING_FOR_PHONE, 45_000).first)
        assertEquals(FailureReason.PHONE_NEVER_REACHED_CAR, StallPlaybook.failure(Engine.ANDROID_AUTO, wireless, ProgressStage.JOINING_WIFI, 15_000).first)
        val (reason, detail) = StallPlaybook.failure(Engine.CARPLAY, wireless, ProgressStage.STARTING_PROJECTION, 20_000)
        assertEquals(FailureReason.LINK_TOO_WEAK, reason)
        assertEquals("no picture 20 s after the iPhone connected", detail)
    }

    @Test fun deadlinesUseTheDefaultUntilThreeSamplesThenScaleTheMedianWithinBounds() {
        val bounds = AdaptiveDeadlines.bounds(ProgressStage.STARTING_PROJECTION, wireless)
        assertEquals(AdaptiveDeadlines.Bounds(20_000, 15_000, 30_000), bounds)
        assertEquals(20_000, AdaptiveDeadlines.deadline(emptyList(), bounds))
        assertEquals(20_000, AdaptiveDeadlines.deadline(listOf(1_000, 1_000), bounds))
        assertEquals(15_000, AdaptiveDeadlines.deadline(listOf(1_000, 1_000, 1_000), bounds))
        assertEquals(25_000, AdaptiveDeadlines.deadline(listOf(9_000, 10_000, 30_000), bounds.copy(max = 40_000)))
        assertEquals(30_000, AdaptiveDeadlines.deadline(listOf(20_000, 20_000, 20_000), bounds))
        assertEquals(10_000L, AdaptiveDeadlines.median(listOf(9_000, 11_000)))
    }

    @Test fun boundsMatchTheAudit() {
        // "Never a silent wait over ~10 s": the waiting deadline over Bluetooth starts at 10 s.
        assertEquals(10_000, AdaptiveDeadlines.bounds(ProgressStage.WAITING_FOR_PHONE, wireless).default)
        assertEquals(10_000L, AdaptiveDeadlines.HINT_MILLIS)
        assertEquals(20_000, AdaptiveDeadlines.bounds(ProgressStage.STARTING_PROJECTION, usb).default)
        assertTrue(AdaptiveDeadlines.bounds(null, wireless).min >= 27_700) // the slowest successful wireless start
    }
}
