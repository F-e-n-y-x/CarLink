package com.andrerinas.openheadunit.aap

import kotlin.random.Random

// CarLink: ported from open-headunit (andreknieriem/open-headunit) P2pGroupIdentityPolicy and
// P2pIdentityRotationPolicy.readsExistingGroup, as merged with PR #1006 ("Native AA: reconnect to a
// network that is still there, instead of rebuilding it every time"). Upstream measured 1.1 s to SSL
// when the phone rejoined a kept group against 8.9-9.5 s when the group was recreated under a new
// name (PR #1006, issue #976). Differences from upstream are marked "CarLink" below.

/** The identity a Native AA P2P group is asked for. */
data class P2pGroupIdentity(
    val networkName: String,
    val passphrase: String,
    /** Ask the platform to keep the profile, so the same pair comes back on the next create. */
    val persistent: Boolean,
)

/** The pair kept between bring-ups. */
data class StoredP2pIdentity(val networkName: String, val passphrase: String)

/**
 * Names the Native AA Wi-Fi Direct group, and decides whether the name outlives the group.
 *
 * Every create used to mint a new name and passphrase and build a temporary group, so the phone was
 * provisioned for a new network on every session and could never rejoin one it had saved. A kept
 * pair, asked for as a persistent group, makes a reconnect a saved-network join. The name and
 * passphrase are only ever replaced together: a name reused with a different passphrase is the one
 * combination a phone's stored profile cannot recover from without help.
 */
object P2pGroupIdentityPolicy {

    const val NAME_PREFIX = "DIRECT-"
    const val MAX_NAME_BYTES = 32
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_PASSPHRASE_LENGTH = 63
    const val PASSPHRASE_LENGTH = 12
    const val DEFAULT_SUFFIX = "HeadUnit"
    private const val MAX_SUFFIX_CHARS = 20

    /** The first level where `WifiP2pConfig.Builder` can name the group this app creates. */
    const val NAMED_CREATE_SDK = 29

    private val CODE_POOL = ('A'..'Z') + ('0'..'9')
    private val PASSPHRASE_POOL = ('A'..'Z') + ('a'..'z') + ('0'..'9')
    private val NAME_SHAPE = Regex("^DIRECT-[a-zA-Z0-9]{2}.*")

    /** What to ask for, what to write back if a pair was just drawn, and why, for the log. */
    data class Choice(
        val identity: P2pGroupIdentity,
        val toStore: StoredP2pIdentity?,
        val reason: String,
    )

    /**
     * @param deviceName the name the group is suffixed with. CarLink: the car name the host shows
     *   the phone (DiAuto's `vehicle-display-name`), not the P2P device name or "HeadUnit".
     */
    fun decide(
        keepIdentity: Boolean,
        stored: StoredP2pIdentity?,
        deviceName: String?,
        random: Random = Random.Default,
    ): Choice {
        if (!keepIdentity) {
            val fresh = mint(deviceName, random)
            return Choice(
                identity = P2pGroupIdentity(fresh.networkName, fresh.passphrase, persistent = false),
                toStore = null,
                reason = "group identity: a new network on every create (${fresh.networkName}), " +
                    "as the setting asks; the phone will be set up for it over Bluetooth.",
            )
        }
        if (stored != null && isValid(stored) && matchesName(stored, deviceName)) {
            return Choice(
                identity = P2pGroupIdentity(stored.networkName, stored.passphrase, persistent = true),
                toStore = null,
                reason = "group identity: asking for the kept network ${stored.networkName} again, " +
                    "so a phone that saved it can rejoin without being set up for a new one.",
            )
        }
        val fresh = mint(deviceName, random)
        val why = when {
            stored == null -> ""
            !isValid(stored) -> " (the stored one was not usable)"
            // CarLink: the car was renamed. Name and passphrase are replaced together.
            else -> " (the car's name changed from the stored ${stored.networkName})"
        }
        return Choice(
            identity = P2pGroupIdentity(fresh.networkName, fresh.passphrase, persistent = true),
            toStore = fresh,
            reason = "group identity: no kept network yet$why, so ${fresh.networkName} is drawn now " +
                "and kept for every later create.",
        )
    }

    /** A new pair. */
    fun mint(deviceName: String?, random: Random = Random.Default): StoredP2pIdentity =
        StoredP2pIdentity(networkName(newCode(random), deviceName), newPassphrase(random))

    /**
     * `DIRECT-xy-<suffix>`. The shape is the platform's: `WifiP2pConfig.Builder.setNetworkName`
     * rejects anything that does not start with `DIRECT-` and two alphanumerics, or that runs past
     * 32 bytes. The suffix keeps only ASCII letters and digits, so it is one byte a character.
     */
    fun networkName(code: String, deviceName: String?): String {
        val suffix = deviceName
            ?.filter { it.isLetterOrDigit() && it.code < 128 }
            ?.take(MAX_SUFFIX_CHARS)
            ?.ifEmpty { null } ?: DEFAULT_SUFFIX
        return "$NAME_PREFIX$code-$suffix".take(MAX_NAME_BYTES)
    }

    fun newCode(random: Random = Random.Default): String =
        "${CODE_POOL.random(random)}${CODE_POOL.random(random)}"

    fun newPassphrase(random: Random = Random.Default): String =
        (1..PASSPHRASE_LENGTH).map { PASSPHRASE_POOL.random(random) }.joinToString("")

    /** The two characters after `DIRECT-`, or null when [name] is not the shape this object builds. */
    fun codeOf(name: String): String? =
        if (isValidName(name)) name.substring(NAME_PREFIX.length, NAME_PREFIX.length + 2) else null

    fun isValidName(name: String): Boolean =
        NAME_SHAPE.matches(name) && name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

    fun isValidPassphrase(passphrase: String): Boolean =
        passphrase.length in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH &&
            passphrase.all { it.code in 0x20..0x7E }

    fun isValid(stored: StoredP2pIdentity): Boolean =
        isValidName(stored.networkName) && isValidPassphrase(stored.passphrase)

    /** CarLink: whether [stored] still carries the suffix [deviceName] would give it. */
    private fun matchesName(stored: StoredP2pIdentity, deviceName: String?): Boolean {
        val code = codeOf(stored.networkName) ?: return false
        return stored.networkName == networkName(code, deviceName)
    }

    /**
     * Whether a group already up is read rather than torn down and asked for again (upstream
     * `P2pIdentityRotationPolicy.readsExistingGroup`).
     *
     * From [NAMED_CREATE_SDK] the whole pair is the test, not just the name: a survivor matched on
     * name alone served the phone the old passphrase after the stored one changed. Below it the
     * platform names the group, so upstream reads any group this unit owns. CarLink: below it, only
     * a group whose name this engine recorded ([ownedByThisEngine]), never another app's group.
     */
    fun readsExistingGroup(
        sdkInt: Int,
        isGroupOwner: Boolean,
        liveName: String?,
        requestedName: String,
        livePassphrase: String?,
        requestedPassphrase: String,
        ownedByThisEngine: Boolean,
    ): Boolean = isGroupOwner && if (sdkInt < NAMED_CREATE_SDK) ownedByThisEngine
        else liveName == requestedName && livePassphrase == requestedPassphrase
}
