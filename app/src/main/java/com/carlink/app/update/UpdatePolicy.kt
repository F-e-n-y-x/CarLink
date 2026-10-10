package com.carlink.app.update

import com.carlink.core.connection.SessionRequest
import com.carlink.core.connection.SessionState

/** What the updater remembers (stored with CarLink's screen preferences, ui/UiPreferences). */
interface UpdatePrefs {
    /** "Automatically check for updates"; on by default. */
    var autoCheckUpdates: Boolean
    /** The tag the driver chose "Ignore this version" for, or null. */
    var ignoredUpdateTag: String?
    /** Wall-clock time of the last check attempt, 0 for never. */
    var lastUpdateCheckMillis: Long
}

/** When the updater may check and when it may interrupt. Pure rules, unit-tested. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MILLIS: Long = 6 * 60 * 60 * 1000L

    /**
     * An automatic check is due when the build can update, the driver left automatic checks on,
     * and the last attempt is at least [CHECK_INTERVAL_MILLIS] old. A clock that went backwards
     * (head units without a real-time clock boot in the past) counts as due.
     */
    fun autoCheckDue(now: Long, lastCheck: Long, enabled: Boolean, supported: Boolean): Boolean =
        supported && enabled && (lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MILLIS)

    /**
     * No projection session is starting, running or waiting for the driver: CarLink never checks
     * for or offers an update while the driver may be using CarPlay or Android Auto.
     */
    fun sessionQuiet(state: SessionState, pendingSwitch: SessionRequest?): Boolean =
        pendingSwitch == null && (state is SessionState.Idle || state is SessionState.Failed)

    /** Offer [release]: newer than [current], and not the version the driver ignored (unless they asked). */
    fun shouldOffer(release: ReleaseInfo, current: AppVersion, ignoredTag: String?, manual: Boolean): Boolean =
        release.version > current && (manual || !release.tag.equals(ignoredTag, ignoreCase = true))
}
