package com.carlink.core.connection

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * "Resume music after reconnecting" (opt-in, both engines; one instance per process and engine).
 *
 * The phone's play state is saved at each change it reports, so it survives the car switching
 * off. When a session shows its first picture, [onSessionShown] arms a check if the phone was
 * playing as the last session ended; [RESUME_DELAY_MS] later, [check] presses play once, unless the
 * phone has played in this session already (it resumed by itself, or the driver played and paused).
 *
 * The new session's first reports ("paused", which the phone often sends as it connects) are not
 * saved until the check has run or the phone has played: they would overwrite the state the last
 * session ended with before the check could read it.
 */
class ResumeMusic(private val prefs: SharedPreferences, private val key: String) {
    private var lastReport: Boolean? = null // this session's
    private var settled = false // the phone played in this session, or its check ran: reports are saved
    private var armed = false
    private var generation = 0 // a check scheduled for an earlier picture or session does nothing

    @Synchronized
    fun onPhonePlaying(playing: Boolean) {
        lastReport = playing
        if (playing) settled = true
        if (settled) save(playing)
    }

    /** Returns the token [check] needs. */
    @Synchronized
    fun onSessionShown(enabled: Boolean): Int {
        armed = enabled && !settled && prefs.getBoolean(key, false)
        return ++generation
    }

    @Synchronized
    fun onSessionEnded() {
        generation++
        lastReport = null
        settled = false
        armed = false
    }

    /** Whether to press play now; called once, [RESUME_DELAY_MS] after [onSessionShown] returned [token]. */
    @Synchronized
    fun check(token: Int): Boolean {
        if (token != generation) return false
        val press = armed && !settled
        armed = false
        if (!settled) {
            settled = true
            // Pressing play makes the phone report "playing", which saves it.
            if (!press) save(lastReport ?: false)
        }
        return press
    }

    private fun save(playing: Boolean) {
        if (prefs.getBoolean(key, false) != playing) prefs.edit().putBoolean(key, playing).apply()
    }

    /**
     * Runs [check] [RESUME_DELAY_MS] after each first picture on the main thread and calls [press]
     * when it says so. [enabled] is read at the first picture.
     */
    fun scheduleAfterFirstPicture(enabled: Boolean, press: () -> Boolean, tag: String) {
        val token = onSessionShown(enabled)
        main.postDelayed({
            if (check(token)) Log.i(tag, "resume music: the phone was playing when the last session ended; play sent=${press()}")
        }, RESUME_DELAY_MS)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    companion object {
        /** Long enough for a phone that resumes by itself (iPhones usually do) to say so first. */
        const val RESUME_DELAY_MS = 5_000L
    }
}
