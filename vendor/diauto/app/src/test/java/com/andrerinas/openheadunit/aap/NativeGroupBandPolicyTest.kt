package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.NativeGroupBandPolicy.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frequencies are real ones: 2437 and 2462 are the 2.4 GHz channels this rig's groups have come
 * up on, 5180 and 5745 the 5 GHz ones.
 */
class NativeGroupBandPolicyTest {

    @Test
    fun `the default is unchanged - 5GHz is what Native AA asks for`() {
        assertEquals(Band.GHZ_5, NativeGroupBandPolicy.bandFor(force24Ghz = false))
    }

    @Test
    fun `the override asks for 2_4GHz`() {
        assertEquals(Band.GHZ_2_4, NativeGroupBandPolicy.bandFor(force24Ghz = true))
    }

    @Test
    fun `a 5GHz request that came up on 2_4GHz is remade`() {
        assertTrue(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 2437, retriesSoFar = 0, maxRetries = 2))
        assertTrue(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 2462, retriesSoFar = 1, maxRetries = 2))
    }

    @Test
    fun `a group deliberately put on 2_4GHz is never remade - the round would measure nothing otherwise`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_2_4, 2437, retriesSoFar = 0, maxRetries = 2))
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_2_4, 2462, retriesSoFar = 1, maxRetries = 2))
    }

    @Test
    fun `a 5GHz request that came up on 5GHz is left alone`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 5180, retriesSoFar = 0, maxRetries = 2))
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 5745, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `the retry budget still bounds it`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 2437, retriesSoFar = 2, maxRetries = 2))
    }

    @Test
    fun `an unknown frequency is not a mismatch`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 0, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `a standard-fallback group is never remade - nobody asked for a band`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.UNSPECIFIED, 2437, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `the labels are the ones the log and the briefs grep for`() {
        assertEquals("2.4GHz", NativeGroupBandPolicy.label(Band.GHZ_2_4))
        assertEquals("5GHz", NativeGroupBandPolicy.label(Band.GHZ_5))
    }

    // --- CarLink (open-headunit 30310b93): remembered banded refusal ---

    private val build = "Lenovo/L38043/L38043:10/QP1A/eng.test:userdebug/test-keys"

    @Test
    fun `the band request is skipped only on the build that refused it`() {
        assertTrue(NativeGroupBandPolicy.skipsBandedRequest(false, build, build))
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(false, null, build))
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(false, "", build))
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(false, build, "$build.1"))
    }

    @Test
    fun `a forced 2_4GHz band keeps asking for that band`() {
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(true, build, build))
    }
}
