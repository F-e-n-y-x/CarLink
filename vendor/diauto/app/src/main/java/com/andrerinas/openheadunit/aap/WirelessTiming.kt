package com.andrerinas.openheadunit.aap

// CarLink: new file. Always-on wireless bring-up timing (see docs/VENDOR_CHANGES.md).

import android.os.SystemClock
import android.util.Log
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.DiagnosticJournal

/**
 * Records each wireless phase once per bring-up as one line,
 * `WIRELESS_TIMING <phase> start+<ms>ms [detail]`, timed from [begin] (a host's wireless start or
 * a poke request). Phases before the first [begin] are timed from the first phase.
 */
class WirelessTimeline(
    private val nowMillis: () -> Long,
    private val sink: (String) -> Unit,
) {
    enum class Phase(val label: String) {
        WIFI_INIT("wifi-init"),
        GROUP_CREATE("group-create"),
        GROUP_CREATED("group-created"),
        GROUP_INFO("group-info"),
        CREDENTIALS("credentials"),
        POKE_START("poke-start"),
        POKE_CONNECTED("poke-connected"),
        PHONE_RFCOMM("phone-rfcomm"),
        WIFI_START_REQUEST("wifi-start-request"),
        WIFI_INFO_RESPONSE("wifi-info-response"),
        PHONE_JOINED("phone-joined"),
        TCP_ACCEPTED("tcp-accepted"),
        FIRST_FRAME("first-frame"),
    }

    private val lock = Any()
    private var originMillis: Long? = null
    private val seen = HashSet<Phase>()

    /**
     * Starts a new bring-up timed from now; phases may be logged again. A begin within
     * [unlessWithinMillis] of the previous one is the same bring-up (the host sends its start and
     * its poke back to back) and is ignored.
     */
    fun begin(reason: String, unlessWithinMillis: Long = 0L) {
        val line = synchronized(lock) {
            val now = nowMillis()
            val origin = originMillis
            if (origin != null && now - origin in 0 until unlessWithinMillis) return
            originMillis = now
            seen.clear()
            "WIRELESS_TIMING begin start+0ms reason=$reason"
        }
        runCatching { sink(line) }
    }

    /** Marks [phase] the first time in this bring-up only; returns the line it logged, or null. */
    fun mark(phase: Phase, detail: String = ""): String? {
        val line = synchronized(lock) {
            if (!seen.add(phase)) return null
            val now = nowMillis()
            val origin = originMillis ?: now.also { originMillis = it }
            buildString {
                append("WIRELESS_TIMING ").append(phase.label)
                append(" start+").append(now - origin).append("ms")
                if (detail.isNotEmpty()) append(' ').append(detail)
            }
        }
        runCatching { sink(line) }
        return line
    }
}

/** The `:aa` process's timeline: logcat and DiAuto's diagnostic journal, whatever the log level. */
object WirelessTiming {
    private val timeline = WirelessTimeline(SystemClock::elapsedRealtime) { line ->
        Log.i(AppLog.TAG, line)
        DiagnosticJournal.record(Log.INFO, line)
    }

    /** Same bring-up when within this of the previous begin: the host's start, then its poke. */
    const val SAME_BRING_UP_MILLIS = 5_000L

    fun begin(reason: String, unlessWithinMillis: Long = 0L) = timeline.begin(reason, unlessWithinMillis)

    fun mark(phase: WirelessTimeline.Phase, detail: String = "") {
        timeline.mark(phase, detail)
    }
}
