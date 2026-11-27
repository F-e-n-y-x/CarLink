package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.HandsFreeRestorePolicy.Action
import com.andrerinas.openheadunit.aap.HandsFreeRestorePolicy.ClientLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HandsFreeRestorePolicyTest {

    private val hfpAg = BluetoothWakePolicy.HFP_AG_UUID
    private val hspAg = BluetoothWakePolicy.HSP_AG_UUID

    @Test fun aPokeThatReachedTheHandsFreeGatewayIsRestoredOnAUnitWithItsOwnHandsFree() {
        assertTrue(HandsFreeRestorePolicy.shouldRestore(true, hfpAg, standingInForHfp = false, apple = false))
        assertTrue(HandsFreeRestorePolicy.shouldRestore(true, hspAg, standingInForHfp = false, apple = false))
    }

    @Test fun aPokeThatReachedNothingTookNothing() {
        assertFalse(HandsFreeRestorePolicy.shouldRestore(false, null, standingInForHfp = false, apple = false))
        assertFalse(HandsFreeRestorePolicy.shouldRestore(false, hfpAg, standingInForHfp = false, apple = false))
    }

    @Test fun aUnitWithoutItsOwnHandsFreeHasNoClientToGiveTheLinkTo() {
        assertFalse(HandsFreeRestorePolicy.shouldRestore(true, hfpAg, standingInForHfp = true, apple = false))
    }

    @Test fun anIphoneIsNeverTouched() {
        assertFalse(HandsFreeRestorePolicy.shouldRestore(true, hfpAg, standingInForHfp = false, apple = true))
    }

    @Test fun anotherRecordIsNotAHandsFreeSlot() {
        val other = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        assertFalse(HandsFreeRestorePolicy.shouldRestore(true, other, standingInForHfp = false, apple = false))
    }

    @Test fun clientStatesMapFromTheProfileConstants() {
        assertEquals(ClientLink.DISCONNECTED, ClientLink.of(0))
        assertEquals(ClientLink.CONNECTING, ClientLink.of(1))
        assertEquals(ClientLink.CONNECTED, ClientLink.of(2))
        assertEquals(ClientLink.DISCONNECTED, ClientLink.of(3))
        assertEquals(ClientLink.UNREADABLE, ClientLink.of(null))
    }

    @Test fun aLinkThatIsBackNeedsNothing() {
        assertEquals(Action.DONE, HandsFreeRestorePolicy.next(0, ClientLink.CONNECTED, busy = false, callActive = false))
        assertEquals(Action.DONE, HandsFreeRestorePolicy.next(0, ClientLink.CONNECTED, busy = true, callActive = true))
    }

    @Test fun aDroppedLinkIsAskedBackOnEveryAttempt() {
        HandsFreeRestorePolicy.ATTEMPT_DELAYS_MS.indices.forEach {
            assertEquals(Action.CONNECT, HandsFreeRestorePolicy.next(it, ClientLink.DISCONNECTED, busy = false, callActive = false))
        }
    }

    @Test fun theHandshakeAHandoffOrACallPutItOff() {
        assertEquals(Action.DEFER, HandsFreeRestorePolicy.next(0, ClientLink.DISCONNECTED, busy = true, callActive = false))
        assertEquals(Action.DEFER, HandsFreeRestorePolicy.next(0, ClientLink.DISCONNECTED, busy = false, callActive = true))
    }

    @Test fun aLinkOnItsWayIsNotAskedTwice() {
        assertEquals(Action.WAIT, HandsFreeRestorePolicy.next(1, ClientLink.CONNECTING, busy = false, callActive = false))
    }

    @Test fun anUnreadableClientIsAskedOnceOnly() {
        assertEquals(Action.CONNECT, HandsFreeRestorePolicy.next(0, ClientLink.UNREADABLE, busy = false, callActive = false))
        assertEquals(Action.GIVE_UP, HandsFreeRestorePolicy.next(1, ClientLink.UNREADABLE, busy = false, callActive = false))
    }

    @Test fun attemptsRunOut() {
        val spent = HandsFreeRestorePolicy.ATTEMPT_DELAYS_MS.size
        assertEquals(Action.GIVE_UP, HandsFreeRestorePolicy.next(spent, ClientLink.DISCONNECTED, busy = false, callActive = false))
    }

    @Test fun attemptsAreSpacedOutInOrder() {
        val delays = HandsFreeRestorePolicy.ATTEMPT_DELAYS_MS
        assertTrue(delays.isNotEmpty())
        assertEquals(delays.sorted(), delays)
        assertTrue(delays.first() > 0L)
    }

    private val connected = BluetoothWakePolicy.TargetLink.CONNECTED
    private val absent = BluetoothWakePolicy.TargetLink.ABSENT
    private val unreadable = BluetoothWakePolicy.TargetLink.UNREADABLE
    private val linkUp = BluetoothWakePolicy.HandsFreeLink.CONNECTED
    private val noLink = BluetoothWakePolicy.HandsFreeLink.ABSENT

    @Test fun verdictsComeFromTheEndOfARun() {
        assertEquals(HandsFreeRestorePolicy.Verdict.WORKS, HandsFreeRestorePolicy.verdictAfter(true))
        assertEquals(HandsFreeRestorePolicy.Verdict.FAILS, HandsFreeRestorePolicy.verdictAfter(false))
        assertEquals(HandsFreeRestorePolicy.Verdict.FAILS, HandsFreeRestorePolicy.verdictAfter(null))
        assertEquals(HandsFreeRestorePolicy.Verdict.UNKNOWN, HandsFreeRestorePolicy.Verdict.of(0))
        assertEquals(HandsFreeRestorePolicy.Verdict.UNKNOWN, HandsFreeRestorePolicy.Verdict.of(99))
        assertEquals(HandsFreeRestorePolicy.Verdict.FAILS,
            HandsFreeRestorePolicy.Verdict.of(HandsFreeRestorePolicy.Verdict.FAILS.ordinal))
    }

    @Test fun anUnmeasuredOrWorkingUnitKeepsThePoke() {
        listOf(HandsFreeRestorePolicy.Verdict.UNKNOWN, HandsFreeRestorePolicy.Verdict.WORKS).forEach {
            assertTrue(HandsFreeRestorePolicy.mayTakeSlot(false, it, connected, linkUp))
            assertTrue(HandsFreeRestorePolicy.mayTakeSlot(false, it, connected, noLink))
        }
    }

    @Test fun whereRestoreFailsAPhoneAlreadyOnTheUnitIsLeftAlone() {
        val fails = HandsFreeRestorePolicy.Verdict.FAILS
        assertFalse(HandsFreeRestorePolicy.mayTakeSlot(false, fails, connected, noLink))
        assertFalse(HandsFreeRestorePolicy.mayTakeSlot(false, fails, connected, linkUp))
        assertFalse(HandsFreeRestorePolicy.mayTakeSlot(false, fails, unreadable, noLink))
        assertFalse(HandsFreeRestorePolicy.mayTakeSlot(false, fails, absent, linkUp))
    }

    @Test fun whereRestoreFailsAPhoneWithNoConnectionIsStillWoken() {
        assertTrue(HandsFreeRestorePolicy.mayTakeSlot(false, HandsFreeRestorePolicy.Verdict.FAILS, absent, noLink))
    }

    @Test fun theStandInAlwaysKeepsThePoke() {
        assertTrue(HandsFreeRestorePolicy.mayTakeSlot(true, HandsFreeRestorePolicy.Verdict.FAILS, connected, linkUp))
    }
}
