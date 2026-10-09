package com.andrerinas.openheadunit.connection

// CarLink: new file, a minimal port of open-headunit's NativeRefreshPolicy create window
// (see docs/VENDOR_CHANGES.md).

/**
 * Whether a Native AA group we asked for is still owed an answer.
 *
 * `isGroupCreatingOrCreated` is cleared by every "not connected" P2P broadcast, including the sticky
 * one delivered when the receiver registers and the one the remove-before-create provokes. A
 * credential refresh landing in that gap (the manual poke asks for one when it finds no
 * credentials) saw "no group, nothing creating" and started a second bring-up underneath the first:
 * two "Attempt 0" creates, one BUSY, a remove of the winner's group and a retry 2 s later, about
 * 4 s on the user's tablet. Upstream fixed the same race with this window.
 */
internal object NativeCreateWindow {
    /** How long a create is given to answer before a refresh or bring-up stops waiting on it. */
    const val CREATE_GRACE_MS = 15_000L

    enum class RefreshAction { REDELIVER, WAIT, CREATE }

    fun inFlight(requestedAtMs: Long, nowMs: Long): Boolean =
        requestedAtMs != 0L && nowMs - requestedAtMs in 0 until CREATE_GRACE_MS

    fun refresh(groupExists: Boolean, createInFlight: Boolean, creatingOrCreated: Boolean): RefreshAction = when {
        groupExists -> RefreshAction.REDELIVER
        createInFlight || creatingOrCreated -> RefreshAction.WAIT
        else -> RefreshAction.CREATE
    }

    /** When a WAIT should ask again: once the grace is up, plus a little. */
    fun recheckDelayMs(requestedAtMs: Long, nowMs: Long): Long =
        (CREATE_GRACE_MS - (nowMs - requestedAtMs)).coerceIn(0L, CREATE_GRACE_MS) + 500L
}
