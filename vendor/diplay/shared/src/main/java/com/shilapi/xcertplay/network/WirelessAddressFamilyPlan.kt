package com.shilapi.xcertplay.network

// CarLink: new file (see docs/VENDOR_CHANGES.md).

/**
 * Which address family the wireless endpoint advertises first, and how long it may wait for the
 * iPhone's first AirPlay TCP.
 *
 * Upstream publishes IPv4 first on every access-point backend (PR #283) and flips the remembered
 * family after a 30 s first-TCP timeout (PR #474). On Wi-Fi Direct the IPv4 path needs the
 * group owner's DHCP to reach the iPhone right after it associates; on the user's tablet the first
 * Offers were lost and iOS's DHCP backoff cost ~12 s of a 14.7 s StartSession-to-TCP wait. The
 * group's scoped IPv6 link-local address exists at association and needs no DHCP; DiPlay 0.2.12
 * advertised it on Wi-Fi Direct and connected on Android 13 and 15 phones and tablets (DiPlay #418).
 * It failed on an Android 9 head unit whose link-local reply route had no matching rule (#283).
 *
 * So, with no remembered family, Wi-Fi Direct on Android 13+ tries IPv6 first as a guess with a
 * short first-TCP deadline; a timeout flips the remembered family to IPv4 (upstream's mechanism)
 * and a TCP on the guessed family remembers it, after which the normal deadline applies.
 */
internal object WirelessAddressFamilyPlan {
    /** First-TCP deadline for an unproven IPv6 guess: a working link-local path connects in ~2-4 s. */
    const val GUESS_FIRST_TCP_MILLIS = 10_000L
    const val MIN_IPV6_GUESS_SDK = 33
    /** How long a guess waits for the scoped link-local address before using what is there. */
    const val GUESS_ADDRESS_WAIT_MILLIS = 1_000L

    data class Choice(val preferredFamily: String?, val guessed: Boolean)

    /**
     * CarLink: off by default. Measured on the Redmi Pad + iPhone 13 (2026-10-09): IPv6 made the
     * first TCP arrive in 2.0 s instead of 3.6-14.7 s, but the AirPlay handshake on it then took
     * 9.5 s with 4-5 s pauses (retransmission-like) instead of 1.9 s, so the picture came later.
     */
    @Volatile
    @JvmStatic
    var ipv6Guess: Boolean = false

    fun choose(backend: WirelessHotspotBackend, remembered: String?, sdkInt: Int): Choice = when {
        remembered != null -> Choice(remembered, guessed = false)
        ipv6Guess && backend == WirelessHotspotBackend.WIFI_P2P && sdkInt >= MIN_IPV6_GUESS_SDK ->
            Choice(HotspotAddressPolicy.FAMILY_IPV6, guessed = true)
        else -> Choice(null, guessed = false)
    }

    /** The guess only shortens the deadline when the published address really is that family. */
    fun firstTcpTimeoutMillis(choice: Choice, publishedFamily: String): Long =
        if (choice.guessed && choice.preferredFamily == publishedFamily) GUESS_FIRST_TCP_MILLIS
        else WirelessStartupPolicy.FIRST_TCP_MILLIS

    /** The family to remember after the first TCP, or null to leave the preference as it is. */
    fun familyToRemember(choice: Choice, publishedFamily: String, acceptedFamily: String?): String? =
        if (choice.guessed && choice.preferredFamily == publishedFamily && acceptedFamily == publishedFamily) {
            publishedFamily
        } else null
}
