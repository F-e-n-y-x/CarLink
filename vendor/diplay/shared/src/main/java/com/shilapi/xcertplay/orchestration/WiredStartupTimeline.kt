package com.shilapi.xcertplay.orchestration

// CarLink: new file. Always-on, INFO-level wired bring-up timing (see docs/VENDOR_CHANGES.md).

/**
 * Records each wired milestone once and reports it as one line:
 * `WIRED_TIMING <milestone> plug+<ms>ms session+<ms>ms [detail]`, where `plug` counts from the
 * first milestone (the iPhone being found) and `session` from CarPlay StartSession (`session=n/a`
 * for milestones before it).
 */
class WiredStartupTimeline(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit,
) {
    enum class Milestone(val label: String) {
        IPHONE_FOUND("iphone-found"),
        DATA_PATHS("data-paths"),
        NCM_ATTACHED("ncm-attached"),
        MFI_DONE("mfi-done"),
        START_SESSION("start-session"),
        NETWORK_CONNECTION("network-connection"),
        BULK_OUT_READY("bulk-out-ready"),
        TCP_ACCEPTED("tcp-accepted"),
        SESSION_ACTIVE("session-active"),
        FIRST_FRAME("first-frame"),
    }

    private val lock = Any()
    private var originNanos: Long? = null
    private var startSessionNanos: Long? = null
    private val seen = HashSet<Milestone>()

    /** Marks [milestone] the first time only; returns the line it logged, or null. */
    fun mark(milestone: Milestone, detail: String = ""): String? {
        val line = synchronized(lock) {
            if (!seen.add(milestone)) return null
            val now = nanoTime()
            val origin = originNanos ?: now.also { originNanos = it }
            if (milestone == Milestone.START_SESSION) startSessionNanos = now
            val session = startSessionNanos?.let { "+${millis(now - it)}ms" } ?: "=n/a"
            buildString {
                append("WIRED_TIMING ").append(milestone.label)
                append(" plug+").append(millis(now - origin)).append("ms")
                append(" session").append(session)
                if (detail.isNotEmpty()) append(' ').append(detail)
            }
        }
        runCatching { sink(line) }
        return line
    }

    /** Maps the wired iAP2 control client's progress lines to milestones. */
    fun onIap2Progress(message: String) {
        when {
            message.startsWith("iap2 authentication accepted") -> mark(Milestone.MFI_DONE)
            message.startsWith("iap2 tx=0x4301") -> mark(Milestone.START_SESSION)
        }
    }

    private fun millis(nanos: Long) = nanos / 1_000_000L
}
