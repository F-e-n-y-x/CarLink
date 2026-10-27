package com.carlink.core.connection

import com.shilapi.xcertplay.orchestration.WirelessStartupTimeline.Phase

/**
 * Wireless CarPlay on Wi-Fi Direct: spots a start that hangs after the iPhone got the Wi-Fi
 * details, and an iPhone that left a running session, in seconds instead of the 20 s phase
 * deadline or the 20 s AirPlay TCP timeout (pure; unit tested; times in ms, any clock).
 *
 * Tablet + iPhone 15, 2026-10-20 (live-tab.txt), every good start: `0x4300`/StartSession ≤ 0.25 s
 * after the Wi-Fi details, association ≤ 5.1 s after them, AirPlay TCP ≤ 2.8 s after the iPhone's
 * DHCP lease. The failures: #s5 02:31:34 no StartSession for 20 s (iPhone joined, leased .254 at
 * +4.5 s, never asked); 02:31:56 StartSession and a lease, then no TCP for 19 s; #s6 02:47:02
 * the iPhone left the group and DiPlay noticed 20.6 s later.
 */
class CarPlayLinkWatch {
    enum class Verdict {
        /** Authenticated and given the Wi-Fi details, but the iPhone never asked to start CarPlay. */
        NO_START_REQUEST,
        /** Asked to start, but the iPhone never joined the car's Wi-Fi: its Wi-Fi is off (Settings). */
        NOT_JOINED,
        /** Joined and leased an address, but never opened AirPlay TCP. */
        NO_TCP,
        /** A running session's iPhone left the Wi-Fi Direct group and did not come back. */
        PHONE_LEFT,
    }

    private var wifiConfigAt: Long? = null
    private var startAt: Long? = null
    private var associatedAt: Long? = null
    private var leasedAt: Long? = null
    private var tcp = false
    private var connected = false
    private var hadClient = false
    private var goneSince: Long? = null
    private var reported = false

    /** A new bring-up (DiPlay restarts inside the session, or a new attempt): forget everything. */
    fun reset() {
        wifiConfigAt = null; startAt = null; associatedAt = null; leasedAt = null
        tcp = false; connected = false; hadClient = false; goneSince = null; reported = false
    }

    fun onMilestone(phase: Phase, now: Long) {
        when (phase) {
            Phase.WIFI_CONFIG -> if (wifiConfigAt == null) wifiConfigAt = now
            Phase.START_SESSION -> if (startAt == null) startAt = now
            Phase.PEER_ASSOCIATED -> if (associatedAt == null) associatedAt = now
            Phase.TCP_ACCEPTED -> tcp = true
            else -> Unit
        }
    }

    /** The picture is up: from now on only [Verdict.PHONE_LEFT] applies. */
    fun onConnected() { connected = true }

    /**
     * The group's clients ([clients]) and how many have a DHCP lease ([leased]; Android 15+
     * reports it, 0 below, which only turns [Verdict.NO_TCP] off).
     */
    fun onClients(clients: Int, leased: Int, now: Long) {
        if (clients > 0) {
            hadClient = true
            if (associatedAt == null) associatedAt = now
            goneSince = null
        } else if (hadClient && goneSince == null) goneSince = now
        if (leased > 0 && leasedAt == null) leasedAt = now
    }

    /** What is wrong at [now], at most once per bring-up; null while all is well or undecided. */
    fun verdict(now: Long): Verdict? {
        if (reported) return null
        val v = when {
            connected -> goneSince?.takeIf { now - it >= PHONE_GONE_MILLIS }?.let { Verdict.PHONE_LEFT }
            tcp -> null
            else -> {
                val config = wifiConfigAt
                val start = startAt
                val leased = leasedAt
                val joined = associatedAt
                when {
                    config == null -> null
                    joined == null -> Verdict.NOT_JOINED.takeIf { now - config >= NOT_JOINED_MILLIS }
                    // Counted from the join: the first join of a network the iPhone has never seen
                    // took 3.9 s and the start request followed it (Galaxy Tab, 2026-10-27 20:03:21);
                    // counting from the Wi-Fi details cut that attempt 0.5 s after the join.
                    start == null -> Verdict.NO_START_REQUEST.takeIf { now - joined >= NO_START_MILLIS }
                    leased != null -> Verdict.NO_TCP.takeIf { now - maxOf(start, leased) >= NO_TCP_MILLIS }
                    else -> null
                }
            }
        }
        if (v != null) reported = true
        return v
    }

    companion object {
        /** StartSession comes ≤ 0.25 s after the Wi-Fi details on every good start; counted from the join. */
        const val NO_START_MILLIS = 4_000L
        /** Association took ≤ 5.1 s after the Wi-Fi details (a never-seen network: 3.3 s). */
        const val NOT_JOINED_MILLIS = 9_000L
        /**
         * TCP came ≤ 2.8 s after the lease on a known network; a never-seen one needs longer (5 s cut
         * a first join on the Galaxy Tab, 2026-10-27 20:03:35, whose retry then worked).
         */
        const val NO_TCP_MILLIS = 8_000L
        /** A roam or a radio hiccup rejoins within this; an iPhone that turned Wi-Fi off does not. */
        const val PHONE_GONE_MILLIS = 2_000L
        /** How often the adapter asks for a [verdict]. */
        const val POLL_MILLIS = 500L
    }
}
