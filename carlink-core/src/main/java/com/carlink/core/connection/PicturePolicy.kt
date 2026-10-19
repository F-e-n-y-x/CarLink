package com.carlink.core.connection

/**
 * Android Auto's stream resolutions (DiAuto `Settings.Resolution` ids). [stream] is the size the
 * phone sends in each orientation (DiAuto `HeadUnitScreenConfig.protoForResolution`).
 */
enum class AaResolution(val id: Int, private val landscape: VideoSize, private val portrait: VideoSize, val label: String) {
    P480(1, VideoSize(800, 480), VideoSize(720, 1280), "480p"),
    P720(2, VideoSize(1280, 720), VideoSize(720, 1280), "720p"),
    P1080(3, VideoSize(1920, 1080), VideoSize(1080, 1920), "1080p");

    fun stream(portraitPanel: Boolean): VideoSize = if (portraitPanel) portrait else landscape
}

/**
 * What "Automatic" picture means on this device, for both engines. Null fields leave the engine's
 * own value (the driver's Advanced choice) alone.
 *
 * @property aaResolutionId DiAuto `resolutionId`; 0 keeps DiAuto's own Automatic (sized to the panel).
 * @property aaCodec DiAuto `video-codec`: "H.264" when the device has no hardware HEVC decoder.
 * @property carPlayScalePercent DiPlay `display_scale_percent`: the CarPlay picture is the panel at this size.
 * @property carPlayHevc DiPlay `hevc_enabled`: false when the device has no hardware HEVC decoder.
 * @property carPlayDirectVideo DiPlay `direct_video_output`: true on slow hardware (a SurfaceView
 *   instead of a TextureView, so no GPU pass per video frame); null leaves the driver's switch alone.
 */
data class PictureChoice(
    val aaResolutionId: Int,
    val aaFps: Int,
    val aaCodec: String?,
    val carPlayScalePercent: Int,
    val carPlayFps: Int,
    val carPlayHevc: Boolean?,
    val summary: String,
    val carPlayDirectVideo: Boolean? = null,
)

/**
 * Automatic picture from the hardware decoder (docs/LOW_END_PERFORMANCE.md, "Picture rules").
 * Automatic never asks for more than it did before this policy existed (Android Auto: DiAuto's
 * own panel-sized resolution at 60 fps; CarPlay: the full panel at 30 fps); it only steps down
 * where the decoder or the device cannot carry that:
 *
 * 1. Codec: H.264 for both engines unless a hardware HEVC decoder exists (DiPlay #13: a wrong HEVC
 *    choice gives a black screen). With hardware HEVC the engines' own codec choice stays.
 * 2. Android Auto resolution: DiAuto's panel class (480p / 720p / 1080p), then down the ladder to
 *    the first size the H.264 decoder carries at 30 fps. A software decoder is held to 720p.
 * 3. Android Auto frame rate: 60 fps only when the decoder carries 60 at that size and the device
 *    is not low-end ([DeviceSignals.lowEnd]); otherwise 30.
 * 4. CarPlay size: the panel at 100 %, 75 % or 50 %, the first the decoder carries at 30 fps
 *    (a software decoder no more than 1280×720 pixels; slow hardware no more than 1600×900).
 *    CarPlay stays at 30 fps.
 * 5. CarPlay video output: on slow hardware ([DeviceSignals.liteRendering]: ≤ 3 GB, weak GPU, slow
 *    CPU, …) DiPlay's Direct video output, a SurfaceView the compositor shows as it is, instead of
 *    a TextureView the app's GPU draws again for every frame (issue #4: lag on a 2 GB stereo).
 */
object PicturePolicy {
    const val AA_AUTOMATIC = 0
    val CARPLAY_SCALES = listOf(100, 75, 50)
    private const val SOFTWARE_MAX_PIXELS = 1280L * 720L
    // ponytail: one pixel cap for every slow unit; keeps 1024x600-1920x720 panels at 100 %, shrinks
    // 1080p and "2K" panels to 75 %. Tune per SoC if reports show a unit that carries more.
    private const val SLOW_HARDWARE_MAX_PIXELS = 1600L * 900L

    /** Automatic as it was before this policy: used when nothing is known about the decoder. */
    val ENGINE_DEFAULTS = PictureChoice(AA_AUTOMATIC, 60, null, 100, 30, null, "engine defaults")

    /** Every size [choose] may ask about, so [DeviceCapabilities.read] probes exactly these. */
    fun probeSizes(panel: VideoSize): Set<VideoSize> =
        AaResolution.entries.map { it.stream(panel.portrait) }.toSet() + CARPLAY_SCALES.map { panel.scaled(it) }

    /** The resolution DiAuto's own Automatic picks for this panel below 1440p (`HeadUnitScreenConfig.recalculate`). */
    fun aaPanelClass(panel: VideoSize): AaResolution = if (panel.portrait) {
        if (panel.width > 720 || panel.height > 1280) AaResolution.P1080 else AaResolution.P720
    } else when {
        panel.width <= 800 && panel.height <= 480 -> AaResolution.P480
        panel.width > 1280 || panel.height > 720 -> AaResolution.P1080
        else -> AaResolution.P720
    }

    fun choose(caps: DeviceCapabilities): PictureChoice {
        val hevcOk = caps.hardwareHevc
        val slow = caps.signals.liteRendering
        val codecRule = (if (hevcOk) ENGINE_DEFAULTS else ENGINE_DEFAULTS.copy(aaCodec = H264, carPlayHevc = false))
            .copy(carPlayDirectVideo = if (slow) true else null)
        val avc = caps.h264
        if (avc == null || avc.maxFps.isEmpty()) {
            return codecRule.copy(summary = "no H.264 decoder answered; engine defaults" + directNote(slow) + lowEndNote(caps.signals))
        }
        val fits = { size: VideoSize -> avc.carries(size) && (avc.hardware || size.pixels <= SOFTWARE_MAX_PIXELS) }

        val portrait = caps.panel.portrait
        val panelClass = aaPanelClass(caps.panel)
        val aa = AaResolution.entries.filter { it <= panelClass }.reversed().firstOrNull { fits(it.stream(portrait)) }
            ?: AaResolution.P480
        val aaFps = if (avc.hardware && !caps.signals.lowEnd && avc.carries(aa.stream(portrait), 60)) 60 else 30
        val scale = CARPLAY_SCALES.firstOrNull { percent ->
            caps.panel.scaled(percent).let { fits(it) && (!slow || it.pixels <= SLOW_HARDWARE_MAX_PIXELS) }
        } ?: CARPLAY_SCALES.last()

        val codec = if (hevcOk) "engine's choice" else H264
        val summary = "Android Auto ${aa.label}${if (aa == panelClass) " (panel)" else ""} $aaFps fps $codec; " +
            "CarPlay $scale% of ${caps.panel} 30 fps $codec${directNote(slow)}; decoder ${avc.name}" +
            (if (avc.hardware) "" else " (software)") + lowEndNote(caps.signals)
        return codecRule.copy(
            aaResolutionId = if (aa == panelClass) AA_AUTOMATIC else aa.id,
            aaFps = aaFps,
            carPlayScalePercent = scale,
            summary = summary,
        )
    }

    private fun directNote(slow: Boolean) = if (slow) " direct video output" else ""

    private fun lowEndNote(signals: DeviceSignals) =
        if (signals.liteRendering) "; low-end: ${signals.liteReasons.joinToString()}" else ""

    const val H264 = "H.264"
}
