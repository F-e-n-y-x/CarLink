package com.carlink.core.connection

import com.carlink.core.connection.aa.AaSessionSettings
import com.carlink.core.core.Transport
import com.carlink.core.settings.Picture
import com.carlink.core.settings.UnifiedSettings
import com.carlink.core.settings.VehicleProfile
import com.carlink.core.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability → Automatic picture rules (docs/LOW_END_PERFORMANCE.md, "Picture rules"). */
class PicturePolicyTest {
    private val strong = DeviceSignals(totalRamMb = 7_600, lowRamDevice = false, cpuCores = 8,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a"))
    private val weak = DeviceSignals(totalRamMb = 1_900, lowRamDevice = false, cpuCores = 4, supportedAbis = listOf("armeabi-v7a"))

    /** A decoder that carries every probed size up to [maxPixels] at [fps] (and 30 fps up to [maxPixels30]). */
    private fun decoder(panel: VideoSize, maxPixels: Long, fps: Int = 60, maxPixels30: Long = maxPixels, hardware: Boolean = true) =
        DecoderInfo("c2.vendor.avc", hardware, PicturePolicy.probeSizes(panel).mapNotNull { size ->
            when {
                size.pixels <= maxPixels -> size to fps
                size.pixels <= maxPixels30 -> size to 30
                else -> null
            }
        }.toMap())

    private fun caps(panel: VideoSize, h264: DecoderInfo?, hevc: Boolean = false, signals: DeviceSignals = strong) =
        DeviceCapabilities(panel, signals, h264, if (hevc) DecoderInfo("c2.vendor.hevc", true, emptyMap()) else null)

    private val p1080 = 1920L * 1080
    private val p720 = 1280L * 720

    @Test fun aStrongDecoderKeepsTheEngineDefaults() {
        val panel = VideoSize(1920, 1080)
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, p1080 * 2), hevc = true))
        assertEquals(PicturePolicy.AA_AUTOMATIC, choice.aaResolutionId)
        assertEquals(60, choice.aaFps)
        assertNull(choice.aaCodec)
        assertEquals(100, choice.carPlayScalePercent)
        assertEquals(30, choice.carPlayFps)
        assertNull(choice.carPlayHevc)
    }

    @Test fun noHardwareHevcMeansH264ForBothEngines() {
        val panel = VideoSize(1280, 720)
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, p1080), hevc = false))
        assertEquals(PicturePolicy.H264, choice.aaCodec)
        assertEquals(false, choice.carPlayHevc)
    }

    @Test fun aDecoderLimitedTo720pStepsAndroidAutoDownAndShrinksCarPlay() {
        val panel = VideoSize(1920, 720) // wide head unit: Android Auto's panel class is 1080p
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, p720, fps = 30)))
        assertEquals(AaResolution.P720.id, choice.aaResolutionId)
        assertEquals(30, choice.aaFps)
        // 1920x720 (1.38 MP) is too much; 75 % is 1440x540 (0.78 MP) and fits.
        assertEquals(75, choice.carPlayScalePercent)
    }

    @Test fun sixtyFpsOnlyWhereTheDecoderCarriesIt() {
        val panel = VideoSize(1920, 1080)
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, maxPixels = p720, fps = 60, maxPixels30 = p1080)))
        assertEquals(PicturePolicy.AA_AUTOMATIC, choice.aaResolutionId) // 1080p still carried at 30
        assertEquals(30, choice.aaFps)
    }

    @Test fun lowEndHardwareRunsAndroidAutoAt30() {
        val panel = VideoSize(1024, 600)
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, p1080), signals = weak))
        assertEquals(PicturePolicy.AA_AUTOMATIC, choice.aaResolutionId) // 720p is the 1024x600 panel's class
        assertEquals(30, choice.aaFps)
        assertEquals(100, choice.carPlayScalePercent)
        assertTrue(choice.summary, choice.summary.contains("low-end"))
    }

    @Test fun aSoftwareDecoderIsHeldTo720pAnd30Fps() {
        val panel = VideoSize(1920, 1080)
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, p1080 * 2, hardware = false)))
        assertEquals(AaResolution.P720.id, choice.aaResolutionId)
        assertEquals(30, choice.aaFps)
        assertEquals(50, choice.carPlayScalePercent) // 960x540 is the first CarPlay size within 1280x720 pixels
    }

    /**
     * Issue #4: a cheap Android 12 stereo, 2 GB, 8 cores at 1.6 GHz, GPU not probed yet, iPhone over
     * USB. CarPlay: the panel at 30 fps in H.264 (as before) and now Direct video output (SurfaceView).
     */
    @Test fun issue4TwoGigabyteStereoGetsTheCheapCarPlayPath() {
        val unit = DeviceSignals(totalRamMb = 1_870, lowRamDevice = false, cpuCores = 8,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"), cpuMaxMhz = 1_600)
        val panel = VideoSize(1280, 720)
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, p1080), signals = unit))
        assertEquals(100, choice.carPlayScalePercent)
        assertEquals(30, choice.carPlayFps)
        assertEquals(false, choice.carPlayHevc)
        assertEquals(true, choice.carPlayDirectVideo)
        assertEquals(30, choice.aaFps)
        val cp = CarPlaySettingsPolicy.map(UnifiedSettings(), null, VehicleProfile.GENERIC, true, picture = choice)
        assertEquals(true, cp.directVideoOutput)
        // The same unit with a "2K" panel: 75 % (1500x900) instead of 2.4 MP per frame.
        val big = VideoSize(2000, 1200)
        assertEquals(75, PicturePolicy.choose(caps(big, decoder(big, p1080 * 2), signals = unit)).carPlayScalePercent)
    }

    @Test fun aWeakGpuOrSlowCpuAloneTurnsOnDirectVideo() {
        val panel = VideoSize(1280, 720)
        fun direct(signals: DeviceSignals) = PicturePolicy.choose(caps(panel, decoder(panel, p1080), signals = signals)).carPlayDirectVideo
        assertNull(direct(strong))
        assertEquals(true, direct(strong.copy(gpuRenderer = "PowerVR Rogue GE8322")))
        assertEquals(true, direct(strong.copy(cpuMaxMhz = 1_600)))
        assertEquals(true, PicturePolicy.choose(caps(panel, null, signals = weak)).carPlayDirectVideo) // also without a probe
        // A strong unit keeps a big panel at 100 %.
        val big = VideoSize(2000, 1200)
        assertEquals(100, PicturePolicy.choose(caps(big, decoder(big, p1080 * 2))).carPlayScalePercent)
    }

    @Test fun anUnansweredProbeKeepsTheEngineDefaults() {
        val panel = VideoSize(1024, 600)
        val none = PicturePolicy.choose(caps(panel, null))
        assertEquals(PicturePolicy.AA_AUTOMATIC, none.aaResolutionId)
        assertEquals(60, none.aaFps)
        assertEquals(100, none.carPlayScalePercent)
        val empty = PicturePolicy.choose(caps(panel, DecoderInfo("c2.vendor.avc", true, emptyMap())))
        assertEquals(PicturePolicy.AA_AUTOMATIC, empty.aaResolutionId)
    }

    @Test fun aVeryWeakDecoderFallsToTheBottomOfEachLadder() {
        val panel = VideoSize(1280, 720)
        // Carries 800x480 (384,000 px) and 640x360, nothing larger.
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, maxPixels = 400_000L)))
        assertEquals(AaResolution.P480.id, choice.aaResolutionId)
        assertEquals(50, choice.carPlayScalePercent)
    }

    @Test fun portraitPanelsProbeDiAutosPortraitStreams() {
        val panel = VideoSize(1080, 1920)
        assertEquals(AaResolution.P1080, PicturePolicy.aaPanelClass(panel))
        assertTrue(VideoSize(1080, 1920) in PicturePolicy.probeSizes(panel))
        assertTrue(VideoSize(720, 1280) in PicturePolicy.probeSizes(panel))
        val choice = PicturePolicy.choose(caps(panel, decoder(panel, p720)))
        assertEquals(AaResolution.P720.id, choice.aaResolutionId)
    }

    @Test fun panelClassesFollowDiAuto() {
        assertEquals(AaResolution.P480, PicturePolicy.aaPanelClass(VideoSize(800, 480)))
        assertEquals(AaResolution.P720, PicturePolicy.aaPanelClass(VideoSize(1024, 600)))
        assertEquals(AaResolution.P720, PicturePolicy.aaPanelClass(VideoSize(1280, 720)))
        assertEquals(AaResolution.P1080, PicturePolicy.aaPanelClass(VideoSize(1920, 720)))
        assertEquals(AaResolution.P1080, PicturePolicy.aaPanelClass(VideoSize(2944, 1840)))
    }

    @Test fun carPlaySizesRoundLikeDiPlay() {
        assertEquals(VideoSize(1440, 540), VideoSize(1920, 720).scaled(75))
        assertEquals(VideoSize(768, 450), VideoSize(1024, 600).scaled(75))
        assertEquals(VideoSize(512, 300), VideoSize(1024, 600).scaled(50))
    }

    @Test fun lowEndSignals() {
        assertFalse(strong.lowEnd)
        assertTrue(weak.lowEnd)
        assertEquals(listOf("1900 MB RAM", "32-bit only", "4 CPU cores"), weak.lowEndReasons)
        assertTrue(strong.copy(lowRamDevice = true).lowEnd)
        assertTrue(strong.copy(totalRamMb = 2_850).lowEnd) // a "3 GB" head unit
        assertFalse(strong.copy(totalRamMb = 3_700).lowEnd) // a "4 GB" one
        assertFalse(strong.copy(totalRamMb = 0).lowEnd) // unknown memory is not a signal
    }

    // ---- The engines' mappings use the choice only for Automatic ------------------------------

    private val weakChoice = PictureChoice(AaResolution.P720.id, 30, PicturePolicy.H264, 75, 30, false, "test")

    @Test fun androidAutoWritesTheChoiceForAutomaticOnly() {
        fun aa(picture: Picture) = AaSessionSettings.values(UnifiedSettings(picture = picture), Transport.USB, null,
            VehicleProfile.GENERIC, "Car", 160, picture = weakChoice).values
        val auto = aa(Picture.AUTOMATIC)
        assertEquals(2, auto["resolutionId"])
        assertEquals(30, auto["fps-limit"])
        assertEquals("H.264", auto["video-codec"])
        val best = aa(Picture.BEST)
        assertEquals(3, best["resolutionId"])
        assertEquals(60, best["fps-limit"])
        assertFalse(best.containsKey("video-codec"))
        // Without a probe (tests, a failed read), Automatic is what it always was.
        val plain = AaSessionSettings.values(UnifiedSettings(), Transport.USB, null, VehicleProfile.GENERIC, "Car", 160).values
        assertEquals(0, plain["resolutionId"])
        assertEquals(60, plain["fps-limit"])
        assertFalse(plain.containsKey("video-codec"))
    }

    @Test fun carPlayWritesTheChoiceForAutomaticOnly() {
        fun cp(picture: Picture) = CarPlaySettingsPolicy.map(UnifiedSettings(picture = picture), WirelessPath.WIFI_DIRECT,
            VehicleProfile.GENERIC, true, picture = weakChoice)
        val auto = cp(Picture.AUTOMATIC)
        assertEquals(75, auto.displayScalePercent)
        assertEquals(30, auto.fps)
        assertEquals(false, auto.hevc)
        assertEquals(true, CarPlaySettingsPolicy.map(UnifiedSettings(), null, VehicleProfile.GENERIC, true,
            picture = weakChoice.copy(carPlayDirectVideo = true)).directVideoOutput)
        assertNull(auto.directVideoOutput) // null in the choice: the driver's switch is left alone
        assertNull(CarPlaySettingsPolicy.map(UnifiedSettings(picture = Picture.CUSTOM), null, VehicleProfile.GENERIC, true,
            picture = weakChoice.copy(carPlayDirectVideo = true)).directVideoOutput)
        val best = cp(Picture.BEST)
        assertEquals(100, best.displayScalePercent)
        assertEquals(60, best.fps)
        assertNull(best.hevc)
        val plain = CarPlaySettingsPolicy.map(UnifiedSettings(), null, VehicleProfile.GENERIC, true)
        assertEquals(100, plain.displayScalePercent)
        assertEquals(30, plain.fps)
        assertNull(plain.hevc)
    }
}
