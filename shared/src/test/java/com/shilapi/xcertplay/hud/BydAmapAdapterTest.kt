package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydAmapAdapterTest {
    @Test
    fun findsTheDiLink3AdapterWhenTheBydOneIsAbsent() {
        // DiLink 3.0, Android 10 (BYD Han EV, GCC): only com.example.amapservice is installed.
        val adapter = BydAmapAdapter.find { it == "com.example.amapservice" }

        assertEquals(BydAmapAdapter.DILINK3, adapter)
        assertTrue(adapter!!.needsSimpleNavigationMode)
    }

    @Test
    fun prefersTheBydAdapterAndLeavesItsClusterModeAlone() {
        val adapter = BydAmapAdapter.find { true }

        assertEquals(BydAmapAdapter.BYD, adapter)
        assertFalse(adapter!!.needsSimpleNavigationMode)
    }

    @Test
    fun noAdapterMeansNoClusterOutput() {
        assertNull(BydAmapAdapter.find { false })
    }

    @Test
    fun clusterModeUsesClusterDebugCommands() {
        assertEquals("service call AutoContainer 2 i32 1000 i32 39 s16 \"\"", BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION.command)
        assertEquals("service call AutoContainer 2 i32 1000 i32 17 s16 \"\"", BydDiLink3ClusterMode.Mode.PROJECTION.command)
        assertEquals("service call AutoContainer 2 i32 1000 i32 18 s16 \"\"", BydDiLink3ClusterMode.Mode.STOCK.command)
    }

    @Test
    fun mapWinsOverGuidanceAndStockIsRestoredOnlyAfterAChange() {
        val mode = BydDiLink3ClusterMode
        assertNull(mode.desired(mapShown = false, guidanceActive = false, requested = null))
        assertEquals(BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION, mode.desired(false, true, null))
        assertEquals(BydDiLink3ClusterMode.Mode.PROJECTION, mode.desired(true, true, BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION))
        assertEquals(BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION, mode.desired(false, true, BydDiLink3ClusterMode.Mode.PROJECTION))
        assertEquals(BydDiLink3ClusterMode.Mode.STOCK, mode.desired(false, false, BydDiLink3ClusterMode.Mode.PROJECTION))
    }

    @Test
    fun clusterModeAcceptsOnlyAnExceptionFreeReply() {
        assertTrue(BydDiLink3ClusterMode.accepted("Result: Parcel(00000000 00000000   '........')"))
        assertFalse(BydDiLink3ClusterMode.accepted("Result: Parcel(ffffffec 00000000 '........')"))
        assertFalse(BydDiLink3ClusterMode.accepted("service: Service AutoContainer does not exist"))
        assertFalse(BydDiLink3ClusterMode.accepted(null))
    }
}
