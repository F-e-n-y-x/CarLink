package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterActivityOutputTest {
    @Test fun staleEditorsCannotChangeOrClearANewerCalibrationPreview() {
        val first = Any()
        val second = Any()
        val a = com.shilapi.xcertplay.airplay.SafeAreaRect(300, 100, 1500, 620)
        val b = com.shilapi.xcertplay.airplay.SafeAreaRect(200, 80, 1600, 640)
        ClusterActivityOutput.beginSafeAreaPreview(first, a)
        ClusterActivityOutput.updateSafeAreaPreview(first, b)
        assertEquals(b, ClusterActivityOutput.previewRect)
        ClusterActivityOutput.beginSafeAreaPreview(second, a)
        ClusterActivityOutput.updateSafeAreaPreview(first, b)
        ClusterActivityOutput.endSafeAreaPreview(first)
        assertEquals(a, ClusterActivityOutput.previewRect)
        ClusterActivityOutput.endSafeAreaPreview(second)
        assertNull(ClusterActivityOutput.previewRect)
    }

    @Test fun overlayStateSurvivesHandoffAndOnlyItsOwnerCanClearIt() {
        val host = Any()
        val newerHost = Any()
        val guidance = com.shilapi.xcertplay.hud.ClusterTurnGuidance(2, 0, 80, "Road",
            remainingMeters = 4200L, remainingSeconds = 630L)
        ClusterActivityOutput.bind(host, 4) { }
        ClusterActivityOutput.setTurnCard(guidance, 20, 50,
            com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.OverlaySize.LARGE)
        ClusterActivityOutput.bind(newerHost, 5) { }
        ClusterActivityOutput.stop(host)
        assertEquals(guidance, ClusterActivityOutput.guidance)
        assertEquals(20, ClusterActivityOutput.cardX)
        assertEquals(50, ClusterActivityOutput.cardY)
        ClusterActivityOutput.stop(newerHost)
        assertNull(ClusterActivityOutput.guidance)
        assertFalse(ClusterActivityOutput.streamActive)
    }

    @Test fun surfaceRecreationRejectsStaleDetachAndRebindsNewHost() {
        val host = Any()
        val oldActivity = Any()
        val newActivity = Any()
        val firstTexture = SurfaceTexture(0)
        val secondTexture = SurfaceTexture(0)
        val first = Surface(firstTexture)
        val second = Surface(secondTexture)
        val events = mutableListOf<Surface?>()
        try {
            ClusterActivityOutput.bind(host, 4) { events.add(it) }
            ClusterActivityOutput.attach(oldActivity, first)
            ClusterActivityOutput.attach(newActivity, second)
            ClusterActivityOutput.detach(oldActivity, first)
            assertSame(second, ClusterActivityOutput.surface)
            assertSame(second, events.last())
            val newHost = Any()
            var rebound: Surface? = null
            ClusterActivityOutput.bind(newHost, 5) { rebound = it }
            assertSame(second, rebound)
            ClusterActivityOutput.stop(host)
            assertSame(second, ClusterActivityOutput.surface)
            ClusterActivityOutput.detach(newActivity, second)
            assertNull(ClusterActivityOutput.surface)
            ClusterActivityOutput.stop(newHost)
        } finally {
            first.release(); second.release()
            firstTexture.release(); secondTexture.release()
        }
    }
}
