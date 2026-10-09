package com.shilapi.xcertplay.orchestration

// CarLink: new file. Always-on, INFO-level wireless bring-up timing (see docs/VENDOR_CHANGES.md).

/**
 * Records each wireless phase once per bring-up and reports it as one line:
 * `WIRELESS_TIMING <phase> start+<ms>ms session+<ms>ms [detail]`, where `start` counts from the
 * controller start (or, after an in-controller restart, from that bring-up) and `session` from
 * CarPlay StartSession (`session=n/a` before it). [restart] begins a new bring-up.
 */
class WirelessStartupTimeline(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit,
) {
    enum class Phase(val label: String) {
        CONTROLLER_START("controller-start"),
        MFI_READY("mfi-ready"),
        BRING_UP("bring-up"),
        RFCOMM_DIAL("rfcomm-dial"),
        HOTSPOT_READY("hotspot-ready"),
        LISTENER_READY("listener-ready"),
        BONJOUR_STARTED("bonjour-started"),
        RFCOMM_CONNECTED("rfcomm-connected"),
        IDENTIFIED("identified"),
        AUTHENTICATED("authenticated"),
        WIFI_CONFIG("wifi-config"),
        START_SESSION("start-session"),
        PEER_ASSOCIATED("peer-associated"),
        PEER_MDNS("peer-mdns"),
        TCP_ACCEPTED("tcp-accepted"),
        SESSION_ACTIVE("session-active"),
        FIRST_FRAME("first-frame"),
        FIRST_TCP_TIMEOUT("first-tcp-timeout"),
    }

    private val lock = Any()
    private var originNanos: Long? = null
    private var startSessionNanos: Long? = null
    private val seen = HashSet<Phase>()

    /** Starts a new bring-up: phases may be logged again, timed from now. */
    fun restart() = synchronized(lock) {
        originNanos = nanoTime()
        startSessionNanos = null
        seen.clear()
    }

    /** Marks [phase] the first time in this bring-up only; returns the line it logged, or null. */
    fun mark(phase: Phase, detail: String = ""): String? {
        val line = synchronized(lock) {
            if (!seen.add(phase)) return null
            val now = nanoTime()
            val origin = originNanos ?: now.also { originNanos = it }
            if (phase == Phase.START_SESSION) startSessionNanos = now
            val session = startSessionNanos?.let { "+${millis(now - it)}ms" } ?: "=n/a"
            buildString {
                append("WIRELESS_TIMING ").append(phase.label)
                append(" start+").append(millis(now - origin)).append("ms")
                append(" session").append(session)
                if (detail.isNotEmpty()) append(' ').append(detail)
            }
        }
        runCatching { sink(line) }
        return line
    }

    /** Maps the wireless iAP2 control client's progress lines to phases. */
    fun onIap2Progress(message: String) {
        when {
            message.startsWith("iap2 identification accepted") -> mark(Phase.IDENTIFIED)
            message.startsWith("iap2 authentication accepted") -> mark(Phase.AUTHENTICATED)
            message.startsWith("iap2 tx=0x5703 accessory") -> mark(Phase.WIFI_CONFIG)
            message.startsWith("iap2 tx=0x4301") -> mark(Phase.START_SESSION)
        }
    }

    private fun millis(nanos: Long) = nanos / 1_000_000L
}
