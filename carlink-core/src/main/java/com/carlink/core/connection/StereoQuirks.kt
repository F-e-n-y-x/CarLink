package com.carlink.core.connection

import android.os.Build
import java.util.Locale

/**
 * Per-stereo quirk profiles: known-good overrides for chips where CarLink's own probes
 * ([DeviceCapabilities], the GPU class) can miss what a report showed. They only change Automatic
 * picture ([Connection.automaticPicture]): a driver who chose a Picture preset or Custom keeps it.
 *
 * Seed only with facts a report, a commit or a doc shows; cite it in [Profile.evidence].
 * Matching is a case-insensitive substring of Build.MANUFACTURER, BOARD, HARDWARE or SOC_MODEL.
 */
object StereoQuirks {

    /** Null leaves the Automatic picture's own value. */
    data class Profile(
        val id: String,
        /** Any one of these, in any Build field, selects the profile. */
        val tokens: List<String>,
        val evidence: String,
        /** False: H.264 for both engines (DiPlay `hevc_enabled` off, DiAuto `video-codec` H.264). */
        val hevc: Boolean? = null,
        /** DiPlay Direct video output (a SurfaceView, no GPU pass per frame). */
        val carPlayDirectVideo: Boolean? = null,
        /** DiAuto `view-mode` SurfaceView. */
        val aaSurfaceView: Boolean? = null,
    ) {
        fun apply(choice: PictureChoice): PictureChoice = choice.copy(
            carPlayHevc = hevc ?: choice.carPlayHevc,
            aaCodec = if (hevc == false) PicturePolicy.H264 else choice.aaCodec,
            carPlayDirectVideo = carPlayDirectVideo ?: choice.carPlayDirectVideo,
            aaSurfaceView = aaSurfaceView ?: choice.aaSurfaceView,
        )

        fun describe(): String = listOfNotNull(
            hevc?.let { "HEVC ${if (it) "on" else "off"}" },
            carPlayDirectVideo?.let { "CarPlay direct video ${if (it) "on" else "off"}" },
            aaSurfaceView?.let { "Android Auto SurfaceView ${if (it) "on" else "off"}" },
        ).joinToString(", ")
    }

    val PROFILES = listOf(
        // UIS8581A / SC9863A (PowerVR GE8322, 8x A55 at 1.6 GHz): CarPlay lagged with the TextureView
        // and Direct video output was the fix (issue #4, commit e0348dcaa); the issue #5 unit reports
        // "sprd s9863a1h10" (docs/LOW_END_PERFORMANCE.md, rounds 3 and 4). The GPU probe finds the
        // GE8322 when GL answers; this keeps the fix when it does not.
        Profile("unisoc-sc9863a", listOf("s9863a"), "issues #4 and #5, docs/LOW_END_PERFORMANCE.md rounds 3-4",
            carPlayDirectVideo = true),
        // Spreadtrum SC7731E (Android 8.1): no hardware HEVC; Android picked OMX.google.hevc.decoder and
        // decoded every CarPlay session in software (vendor/diplay/docs/RELEASE-NOTES-0.2.20.md).
        Profile("spreadtrum-sc7731e", listOf("sc7731e"), "DiPlay 0.2.20 release notes (software HEVC on SC7731E)",
            hevc = false),
    )

    /** The first profile whose token appears in one of [fields] (Build values), or null. */
    fun match(fields: List<String?>): Profile? {
        val haystack = fields.filterNotNull().map { it.lowercase(Locale.ROOT) }
        return PROFILES.firstOrNull { p -> p.tokens.any { t -> haystack.any { it.contains(t) } } }
    }

    /** This device's profile. */
    fun current(): Profile? = match(listOf(Build.MANUFACTURER, Build.BOARD, Build.HARDWARE,
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null))
}
