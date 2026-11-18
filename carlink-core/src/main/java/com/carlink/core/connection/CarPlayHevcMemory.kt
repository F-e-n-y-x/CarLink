package com.carlink.core.connection

import android.content.Context
import com.carlink.core.diagnostics.EventLog

/**
 * "CarPlay HEVC gave no picture on this screen" (DiPlay #13: a wrong HEVC choice is a black screen).
 * A CarPlay attempt with HEVC that stalls in the picture step makes HEVC a suspect: the next attempt
 * tries H.264. Only when that H.264 attempt shows a picture is HEVC blamed, and Automatic picture
 * sends CarPlay H.264 for good. When H.264 stalls too, the cause was elsewhere (iOS 27's "sound but
 * no frames", DiPlay #249) and HEVC stays. Picture presets and Custom never read it
 * ([Connection.automaticPicture] is Automatic only).
 */
class CarPlayHevcMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_picture_memory", Context.MODE_PRIVATE)

    val failed: Boolean get() = prefs.getBoolean(KEY_FAILED, false)

    /** Automatic's CarPlay [choice] with HEVC off where it failed or is on trial; notes what this attempt sends. */
    fun apply(choice: PictureChoice): PictureChoice {
        val out = when {
            choice.carPlayHevc != true -> choice
            failed -> choice.copy(carPlayHevc = false).also {
                EventLog.add("CarLink", "Automatic picture: CarPlay H.264, HEVC gave no picture on this screen before")
            }
            suspect -> choice.copy(carPlayHevc = false).also {
                EventLog.add("CarLink", "Automatic picture: CarPlay H.264 this attempt, HEVC gave no picture on the last one")
            }
            else -> choice
        }
        hevcInUse = out.carPlayHevc == true
        return out
    }

    /** A CarPlay attempt stalled in the picture step without a first frame; true when HEVC became a suspect. */
    fun onNoPicture(): Boolean {
        if (hevcInUse) {
            hevcInUse = false
            suspect = true
            EventLog.add("CarLink", "CarPlay HEVC gave no picture; trying H.264")
            return true
        }
        if (suspect) {
            suspect = false
            EventLog.add("CarLink", "CarPlay H.264 gave no picture either; HEVC is not the cause and stays on")
        }
        return false
    }

    /** A CarPlay attempt showed its picture: an H.264 trial that worked convicts HEVC. */
    fun onPicture() {
        if (suspect && !hevcInUse) {
            prefs.edit().putBoolean(KEY_FAILED, true).apply()
            EventLog.add("CarLink", "CarPlay worked with H.264 after HEVC gave no picture; Automatic uses H.264 for CarPlay from now on")
        }
        suspect = false
    }

    internal companion object {
        const val KEY_FAILED = "carplay_hevc_failed"
        /** Whether the CarPlay attempt now running was started with HEVC (process-wide; written per session). */
        @Volatile var hevcInUse = false
        /** The last HEVC attempt gave no picture; the next one tries H.264 (process-wide, not saved). */
        @Volatile var suspect = false
    }
}
