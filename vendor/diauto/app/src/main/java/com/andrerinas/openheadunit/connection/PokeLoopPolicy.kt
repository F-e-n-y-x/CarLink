package com.andrerinas.openheadunit.connection

// CarLink: new file, ported from open-headunit's EarlyWakePolicy (PR #958, commit b5617bd5; see
// docs/VENDOR_CHANGES.md).

/** When a credential delivery restarts the wake-poke loop, and how long the loop waits first. */
internal object PokeLoopPolicy {
    /** Ceiling on waiting for the AA listeners before poking anyway, and how often to ask. */
    const val LISTENERS_READY_WAIT_MS = 2_000L
    const val LISTENERS_READY_POLL_MS = 100L

    /**
     * Whether a `triggerPoke()` with a new credential key should replace the running loop.
     *
     * False when the only change is credentials arriving under a loop that started without them:
     * the loop re-reads them every pass, so restarting it throws away its head start and pays the
     * entry wait again.
     */
    fun shouldRestartLoop(
        loopActive: Boolean,
        previousKey: Triple<String, String, String>?,
        newKey: Triple<String, String, String>,
    ): Boolean = when {
        !loopActive -> true
        previousKey == newKey -> false
        previousKey != null && isEmptyKey(previousKey) && !isEmptyKey(newKey) -> false
        else -> true
    }

    private fun isEmptyKey(key: Triple<String, String, String>): Boolean =
        key.first.isEmpty() && key.second.isEmpty() && key.third.isEmpty()
}
