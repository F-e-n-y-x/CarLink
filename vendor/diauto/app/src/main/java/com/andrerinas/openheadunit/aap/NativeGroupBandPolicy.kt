package com.andrerinas.openheadunit.aap

/**
 * Which band the Native AA P2P group is requested on, and whether a group that came up on the other
 * one should be torn down and remade.
 *
 * Native AA asks for 5 GHz because that is the band the reporters' working sessions run on, and a
 * group that lands on 2.4 GHz anyway is recreated rather than used. That default is not in question
 * here. What this exists for is the opposite case: two reporter threads describe a link that dies
 * for seconds at a time on a fixed cadence, on 2.4 GHz head units, and nothing on the rig could ever
 * be put on that band to look for it - `createQuietGroup` asks for 5 GHz and
 * `onGroupInfoAvailable` undoes any group that does not come up there, so the rig had two
 * independent reasons never to run the configuration the reports come from.
 *
 * The override is a debug lever, off by default, and it turns off *both* of those: asking for 2.4
 * GHz while leaving the mismatch retry armed would tear the group down as soon as it succeeded.
 * That coupling is the whole reason this is one object with a test rather than two booleans read in
 * two places.
 */
object NativeGroupBandPolicy {

    enum class Band {
        GHZ_2_4,
        GHZ_5,

        /**
         * No band was asked for - the standard `createGroup` fallback, where the platform picks.
         * A group nobody chose a band for cannot be on the wrong one, so it is never remade.
         */
        UNSPECIFIED,
    }

    /** Below this, a P2P group is on 2.4 GHz. The 5 GHz band starts at 5170 MHz. */
    private const val MAX_24GHZ_FREQUENCY_MHZ = 4000

    fun bandFor(force24Ghz: Boolean): Band = if (force24Ghz) Band.GHZ_2_4 else Band.GHZ_5

    /**
     * True when the group that came up must be torn down and remade because it is not on the band
     * that was asked for.
     *
     * Only ever true when 5 GHz was requested: a group we deliberately put on 2.4 GHz is on the band
     * it was asked for, so there is nothing to correct, and retrying it would recreate the group
     * every time it succeeded.
     */
    fun shouldRetryFor5Ghz(
        requested: Band,
        frequencyMhz: Int,
        retriesSoFar: Int,
        maxRetries: Int,
    ): Boolean = requested == Band.GHZ_5 &&
        frequencyMhz in 1..MAX_24GHZ_FREQUENCY_MHZ &&
        retriesSoFar < maxRetries

    /** The band label used in the log, so a capture says which band was asked for and which arrived. */
    fun label(band: Band): String = when (band) {
        Band.GHZ_2_4 -> "2.4GHz"
        Band.GHZ_5 -> "5GHz"
        Band.UNSPECIFIED -> "unspecified"
    }

    /**
     * CarLink (open-headunit 30310b93): whether this bring-up goes straight to the no-band create.
     * Some MediaTek units refuse every banded create on every session and then host the no-band
     * group anyway; the ladder in front of it cost about 8.5 s per connection. The refusal is a
     * property of the firmware, so it is remembered against the build fingerprint (an update asks
     * again). A forced 2.4 GHz band is the user's choice and is still asked for.
     */
    fun skipsBandedRequest(force24Ghz: Boolean, refusedOnFingerprint: String?, currentFingerprint: String): Boolean =
        !force24Ghz && !refusedOnFingerprint.isNullOrEmpty() && refusedOnFingerprint == currentFingerprint

    /** CarLink (open-headunit 79c67d57): the P2P reason for a driver refusal; BUSY and the rest are transient. */
    const val REASON_REFUSED = 0

    /** CarLink (open-headunit 79c67d57): whether a failed rung refused the band rather than being busy or crashing. */
    fun isBandRefusal(reason: Int, crashed: Boolean): Boolean = !crashed && reason == REASON_REFUSED

    /**
     * CarLink (open-headunit 79c67d57): whether the no-band group that followed may be stored. Only on
     * 5 GHz: there skipping the band costs nothing, while a 2.4 GHz group would be chosen every drive.
     */
    fun storesBandedRefusalAt(frequencyMhz: Int): Boolean = frequencyMhz > MAX_24GHZ_FREQUENCY_MHZ
}
