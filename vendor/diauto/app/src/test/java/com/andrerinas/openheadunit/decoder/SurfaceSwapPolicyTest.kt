// CarLink: new file (docs/VENDOR_CHANGES.md, DiAuto, live surface swap).
package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceSwapPolicyTest {

    private fun canSwap(
        hasHardwareCodec: Boolean = true,
        running: Boolean = true,
        softwareHevc: Boolean = false,
        permanentlyFailed: Boolean = false,
        restartPending: Boolean = false,
        newSurfaceValid: Boolean = true,
    ) = SurfaceSwapPolicy.canSwap(
        hasHardwareCodec, running, softwareHevc, permanentlyFailed, restartPending, newSurfaceValid,
    )

    @Test
    fun `a healthy running hardware codec swaps onto a valid surface`() {
        assertTrue(canSwap())
    }

    @Test
    fun `everything else takes the rebuild`() {
        assertFalse("no codec", canSwap(hasHardwareCodec = false))
        assertFalse("stopped", canSwap(running = false))
        assertFalse("bundled software HEVC", canSwap(softwareHevc = true))
        assertFalse("latched failure must be reset by stop()", canSwap(permanentlyFailed = true))
        assertFalse("restart already scheduled", canSwap(restartPending = true))
        assertFalse("invalid surface", canSwap(newSurfaceValid = false))
    }
}
