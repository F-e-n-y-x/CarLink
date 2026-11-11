package com.carlink.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoQuirksTest {
    @Test fun matchesATokenInAnyBuildFieldIgnoringCase() {
        assertEquals("unisoc-sc9863a", StereoQuirks.match(listOf("sprd", "S9863A1H10", null, null))?.id)
        assertEquals("spreadtrum-sc7731e", StereoQuirks.match(listOf("alps", "sp7731e", "sc7731e_1h10", null))?.id)
        assertNull(StereoQuirks.match(listOf("Xiaomi", "pipa", "qcom", "SM8250")))
        assertNull(StereoQuirks.match(listOf(null, null, null, null)))
    }

    @Test fun overridesOnlyWhatTheProfileNames() {
        val base = PicturePolicy.ENGINE_DEFAULTS
        val hevcOff = StereoQuirks.PROFILES.first { it.id == "spreadtrum-sc7731e" }.apply(base)
        assertEquals(false, hevcOff.carPlayHevc)
        assertEquals(PicturePolicy.H264, hevcOff.aaCodec)
        assertNull(hevcOff.carPlayDirectVideo)
        val direct = StereoQuirks.PROFILES.first { it.id == "unisoc-sc9863a" }.apply(base.copy(aaCodec = "H.265", carPlayHevc = true))
        assertEquals(true, direct.carPlayDirectVideo)
        assertEquals("H.265", direct.aaCodec)
        assertEquals(true, direct.carPlayHevc)
        assertEquals(base.aaSurfaceView, direct.aaSurfaceView)
    }

    @Test fun everyProfileCitesItsEvidenceAndOverridesSomething() {
        StereoQuirks.PROFILES.forEach { p ->
            assertTrue(p.id, p.evidence.isNotBlank() && p.tokens.isNotEmpty() && p.describe().isNotEmpty())
            assertTrue(p.id, p.tokens.all { it == it.lowercase() })
        }
        assertEquals(StereoQuirks.PROFILES.size, StereoQuirks.PROFILES.map { it.id }.toSet().size)
    }
}
