package com.shilapi.xcertplay.network

// CarLink: new file. One Wi-Fi Direct name and passphrase per install for wireless CarPlay, asked
// for as a persistent group, so the iPhone rejoins a network it already saved instead of being set
// up for a new DIRECT-… network every session (upstream issue #464 shows the iPhone's list filling
// with them). Modelled on DiAuto's P2pGroupIdentityPolicy (open-headunit PR #1006); see
// docs/VENDOR_CHANGES.md.

/** A group name and passphrase. [toString] never prints the passphrase. */
internal class P2pIdentity(val ssid: String, val passphrase: String) {
    override fun equals(other: Any?) = other is P2pIdentity && other.ssid == ssid && other.passphrase == passphrase
    override fun hashCode() = ssid.hashCode() * 31 + passphrase.hashCode()
    override fun toString() = "P2pIdentity(ssid=$ssid, passphrase=<redacted>)"
}

/** How the identity of this bring-up's group was chosen; [label] goes into `WIRELESS_TIMING`. */
enum class P2pIdentityOutcome(val label: String) {
    /** The stored pair, asked for again. */
    KEPT("kept"),
    /** A pair drawn now (first run, car renamed, stored pair unusable) and stored for later. */
    NEW("new"),
    /** The setting is off: a new random pair every session (upstream behaviour). */
    OFF("off"),
    /** Android cannot name the group (below API 29, or the system-default fallback). */
    SYSTEM("system"),
}

/**
 * Chooses the CarPlay Wi-Fi Direct group identity.
 *
 * The name stays inside DiPlay's reinstall-stable ownership namespace ([P2pOwnership.prefix],
 * `DIRECT-dp<12 hex>-`), so DiPlay's reclaim check and CarLink's leftover-group policy still
 * recognise it, and it ends with the car's name instead of four random characters:
 * `DIRECT-dp3fa91c0b2e7d-CarLink`. The namespace hash comes from the install, so two cars never
 * share a name. Name and passphrase are replaced together, never one without the other.
 *
 * CarPlay keeps its own identity, separate from Android Auto's (`DIRECT-<XY>-<car>` in DiAuto):
 * each engine recognises, reads and removes only its own group, and a phone that saved one
 * engine's network never auto-joins the other engine's group.
 */
internal object P2pStableIdentityPolicy {
    /** The first level where `WifiP2pConfig.Builder` names the group and can make it persistent. */
    const val NAMED_CREATE_SDK = 29
    const val MAX_NAME_BYTES = 32
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_PASSPHRASE_LENGTH = 63
    /** Used when the car's name has no ASCII letter or digit. */
    const val DEFAULT_SUFFIX = "CarPlay"

    class Choice(
        /** What to ask for, or null to keep the per-session random pair. */
        val identity: P2pIdentity?,
        val outcome: P2pIdentityOutcome,
        /** A pair drawn now that must be stored before the group is created. */
        val toStore: P2pIdentity?,
        /** For the log; never contains the name or the passphrase. */
        val reason: String,
    )

    fun decide(
        enabled: Boolean,
        sdkInt: Int,
        stored: P2pIdentity?,
        prefix: String,
        carName: String?,
        newPassphrase: () -> String,
    ): Choice {
        if (!enabled) return Choice(null, P2pIdentityOutcome.OFF, null, "setting_off")
        if (sdkInt < NAMED_CREATE_SDK) return Choice(null, P2pIdentityOutcome.SYSTEM, null, "sdk_below_29")
        val name = networkName(prefix, carName)
        if (stored != null && stored.ssid == name && isValidPassphrase(stored.passphrase)) {
            return Choice(stored, P2pIdentityOutcome.KEPT, null, "stored")
        }
        val fresh = P2pIdentity(name, newPassphrase())
        require(isValidPassphrase(fresh.passphrase)) { "Generated Wi-Fi Direct passphrase is not usable" }
        val why = when {
            stored == null -> "first_use"
            !isValidPassphrase(stored.passphrase) -> "stored_unusable"
            else -> "name_changed"
        }
        return Choice(fresh, P2pIdentityOutcome.NEW, fresh, why)
    }

    /**
     * [prefix] followed by the car's name, ASCII letters and digits only, cut to fit 32 bytes.
     * `WifiP2pConfig.Builder.setNetworkName` needs `DIRECT-` and two alphanumerics, which the
     * prefix already has.
     */
    fun networkName(prefix: String, carName: String?): String {
        val room = MAX_NAME_BYTES - prefix.toByteArray(Charsets.UTF_8).size
        require(room > 0) { "Wi-Fi Direct name prefix is too long" }
        val suffix = carName.orEmpty().filter { it.code < 128 && it.isLetterOrDigit() }.take(room)
            .ifEmpty { DEFAULT_SUFFIX.take(room) }
        return prefix + suffix
    }

    /** Whether [ssid] is a name this install's namespace can produce: the prefix, then 1..room alphanumerics. */
    fun isNamespaceName(ssid: String, prefix: String): Boolean {
        if (!ssid.startsWith(prefix)) return false
        val rest = ssid.removePrefix(prefix)
        val room = MAX_NAME_BYTES - prefix.toByteArray(Charsets.UTF_8).size
        return rest.length in 1..room && rest.all { it.code < 128 && it.isLetterOrDigit() }
    }

    fun isValidPassphrase(passphrase: String): Boolean =
        passphrase.length in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH && passphrase.all { it.code in 0x21..0x7E }

    /**
     * Whether a group already up is read as this bring-up's group instead of being removed and
     * created again (DiAuto's `readsExistingGroup`, API 29+ rule): this device owns it, its whole
     * pair is the one asked for, and it is on a frequency this bring-up's channel plan would ask
     * for first ([fits]). A group on another channel is replaced so the aligned channel logic and
     * a manual channel still apply.
     */
    fun readsLiveGroup(
        identity: P2pIdentity?,
        isGroupOwner: Boolean,
        liveName: String?,
        livePassphrase: String?,
        liveFrequencyMHz: Int,
        fits: (Int) -> Boolean,
    ): Boolean = identity != null && isGroupOwner && liveName == identity.ssid &&
        livePassphrase == identity.passphrase && liveFrequencyMHz > 0 && fits(liveFrequencyMHz)

    /**
     * Whether [frequencyMHz] is what [first], the first request of the channel plan, would give:
     * the exact frequency when it names one, the band for a band-only request, anything for the
     * system default.
     */
    fun fitsFirstRequest(first: P2pCreationRequest, frequencyMHz: Int): Boolean = when {
        first.frequencyMHz != null -> first.frequencyMHz == frequencyMHz
        first.mode == P2pCreationMode.BAND_5_GHZ -> WifiP2pBand.FIVE_GHZ.contains(frequencyMHz)
        first.mode == P2pCreationMode.BAND_2_GHZ -> WifiP2pBand.TWO_GHZ.contains(frequencyMHz)
        else -> true
    }
}
