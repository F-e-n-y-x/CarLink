package com.carlink.app.ui.home

import com.carlink.core.core.Engine
import com.carlink.core.core.LinkProblem
import com.carlink.core.core.LinkStage
import com.carlink.core.core.LinkState
import com.carlink.core.core.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home names the step a failure stopped on, and offers "Forget and re-pair" only where it can help. */
class FailureStepsTest {
    private fun failed(problem: LinkProblem?, step: Int = 0, transport: Transport? = Transport.WIRELESS) =
        LinkState(LinkStage.FAILED, Engine.CARPLAY, transport, "iPhone", step, if (step > 0) 4 else 0, problem = problem)

    @Test fun theProblemPicksTheStep() {
        assertEquals(0, failedStep(failed(LinkProblem.PHONE_DID_NOT_ANSWER, step = 3)))
        assertEquals(2, failedStep(failed(LinkProblem.PHONE_NEVER_REACHED_CAR, step = 2)))
        assertEquals(3, failedStep(failed(LinkProblem.LINK_TOO_WEAK)))
        assertEquals(0, failedStep(failed(LinkProblem.USB, transport = Transport.USB)))
    }

    @Test fun anEngineFailureUsesTheStepTheAttemptWasOn() {
        assertEquals(1, failedStep(failed(LinkProblem.ENGINE, step = 2)))
        assertEquals(3, failedStep(failed(null, step = 4)))
        assertNull("no step known", failedStep(failed(LinkProblem.ENGINE)))
    }

    @Test fun noTimelineWhereNoStepFits() {
        assertNull(failedStep(failed(LinkProblem.PERMISSIONS, step = 1)))
        assertNull(failedStep(failed(LinkProblem.HEAD_UNIT_SERVER_OFF)))
        assertNull("blocked before the start: no step ran", failedStep(failed(LinkProblem.WIFI_OFF)))
        assertNull(failedStep(failed(LinkProblem.CARPLAY_AUTH_MISSING)))
        assertNull("Android Auto on this screen has no transport", failedStep(failed(LinkProblem.ENGINE, step = 2, transport = null)))
        assertNull("not failed", failedStep(failed(LinkProblem.BLUETOOTH).copy(stage = LinkStage.CONNECTING)))
    }

    @Test fun repairIsOfferedForStuckWirelessBluetoothAndHandshake() {
        assertTrue(offersRepair(failed(LinkProblem.PHONE_DID_NOT_ANSWER)))
        assertTrue(offersRepair(failed(LinkProblem.PAIRING_LOST)))
        assertTrue(offersRepair(failed(LinkProblem.ENGINE, step = 2)))
        assertFalse("a radio switch fixes it", offersRepair(failed(LinkProblem.BLUETOOTH_OFF)))
        assertFalse("the stereo's module", offersRepair(failed(LinkProblem.EXTERNAL_BLUETOOTH)))
        assertFalse("Wi-Fi step", offersRepair(failed(LinkProblem.PHONE_REFUSED_WIFI)))
        assertFalse("cable", offersRepair(failed(LinkProblem.USB, transport = Transport.USB)))
    }
}
