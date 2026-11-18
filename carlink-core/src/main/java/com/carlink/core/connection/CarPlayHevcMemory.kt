package com.carlink.core.connection

import android.content.Context
import com.carlink.core.diagnostics.EventLog

/**
 * "CarPlay HEVC gave no picture on this screen" (DiPlay #13: a wrong HEVC choice is a black screen).
 * Once a CarPlay attempt with HEVC on stalls in the picture step without a first frame, Automatic
 * picture sends CarPlay H.264 from the next attempt on, for good. Picture presets and Custom never
 * read it ([Connection.automaticPicture] is Automatic only).
 */
class CarPlayHevcMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_picture_memory", Context.MODE_PRIVATE)

    val failed: Boolean get() = prefs.getBoolean(KEY_FAILED, false)

    /** Automatic's CarPlay [choice] with HEVC off where it failed before; notes what this attempt sends. */
    fun apply(choice: PictureChoice): PictureChoice {
        val out = if (choice.carPlayHevc == true && failed) {
            EventLog.add("CarLink", "Automatic picture: CarPlay H.264, HEVC gave no picture on this screen before")
            choice.copy(carPlayHevc = false)
        } else choice
        hevcInUse = out.carPlayHevc == true
        return out
    }

    /** A CarPlay attempt stalled in the picture step without a first frame; true when HEVC is now held off. */
    fun onNoPicture(): Boolean {
        if (!hevcInUse) return false
        hevcInUse = false
        prefs.edit().putBoolean(KEY_FAILED, true).apply()
        EventLog.add("CarLink", "CarPlay HEVC gave no picture on this screen; Automatic uses H.264 for CarPlay from now on")
        return true
    }

    private companion object {
        const val KEY_FAILED = "carplay_hevc_failed"
        /** Whether the CarPlay attempt now running was started with HEVC (process-wide; written per session). */
        @Volatile var hevcInUse = false
    }
}
