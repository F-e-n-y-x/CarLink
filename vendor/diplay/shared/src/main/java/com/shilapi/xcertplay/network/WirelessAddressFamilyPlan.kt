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

    /**
     * CarLink #93: the car hotspot and LocalOnlyHotspot run IPv4 end to end. On an IPv4-only head unit's SoftAP
     * (Changan CS75 Plus, Allwinner T7) CarPlay stalled after the Wi-Fi credentials while any IPv6 was offered:
     * a link-local AAAA in Bonjour, an IPv6 host in iAP2 0x4301/0x5703, or a family remembered from Same Wi-Fi
     * or flipped after a first-TCP timeout. Wi-Fi Direct and Same Wi-Fi keep both families. Diagnosis: 0nezar0.
     */
    fun ipv4Only(backend: WirelessHotspotBackend): Boolean =
        backend == WirelessHotspotBackend.MANUAL_HOTSPOT || backend == WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT

    /** [addresses] this path may publish, listen on or offer: only the IPv4 ones on an [ipv4Only] backend. */
    fun usable(backend: WirelessHotspotBackend, addresses: List<java.net.InetAddress>): List<java.net.InetAddress> =
        if (ipv4Only(backend)) addresses.filterIsInstance<java.net.Inet4Address>() else addresses

    /** [info] without its IPv6 addresses on an [ipv4Only] backend; a null host is then found by the address wait. */
    fun forPath(info: WirelessHotspotInfo): WirelessHotspotInfo {
        if (!ipv4Only(info.backend)) return info
        val hosts = usable(info.backend, info.hostAddresses)
        return WirelessHotspotInfo(
            info.ssid, info.passphrase, info.security, info.channel, info.frequencyMHz, info.bssid,
            info.interfaceName, info.hostAddress as? java.net.Inet4Address ?: hosts.firstOrNull(), info.bandLabel,
            info.backend, hosts, info.accessPointBssid, info.identityDetail,
        )
    }

    fun choose(backend: WirelessHotspotBackend, remembered: String?, sdkInt: Int): Choice = when {
        // #93: a remembered or flipped family never applies here, and IPv4 is never a guess to remember.
        ipv4Only(backend) -> Choice(HotspotAddressPolicy.FAMILY_IPV4, guessed = false)
        remembered != null -> Choice(remembered, guessed = false)
        ipv6Guess && backend == WirelessHotspotBackend.WIFI_P2P && sdkInt >= MIN_IPV6_GUESS_SDK ->
            Choice(HotspotAddressPolicy.FAMILY_IPV6, guessed = true)
        else -> Choice(null, guessed = false)
    }

    /** The guess only shortens the deadline when the published address really is that family. */
    fun firstTcpTimeoutMillis(choice: Choice, publishedFamily: String): Long =
        if (choice.guessed && choice.preferredFamily == publishedFamily) GUESS_FIRST_TCP_MILLIS
        else WirelessStartupPolicy.FIRST_TCP_MILLIS

    /**
     * The StartSession address list (upstream PR #1071): the published address first, then the interface's
     * others, except a link-local IPv6 address when an IPv4 one is there. Upstream DiPlay #1344: offered both,
     * the iPhone picked fe80:: on car hotspots whose link-local reply has no route, and every attempt timed out.
     * A published link-local address (the family flip after a first-TCP timeout) is always kept.
     */
    fun endpointAddresses(published: java.net.InetAddress, all: List<java.net.InetAddress>): List<java.net.InetAddress> {
        val others = all.filter { it != published }
        val hasIpv4 = published is java.net.Inet4Address || others.any { it is java.net.Inet4Address }
        return listOf(published) + others.filterNot { hasIpv4 && it is java.net.Inet6Address && it.isLinkLocalAddress }
    }

    /** The family to remember after the first TCP, or null to leave the preference as it is. */
    fun familyToRemember(choice: Choice, publishedFamily: String, acceptedFamily: String?): String? =
        if (choice.guessed && choice.preferredFamily == publishedFamily && acceptedFamily == publishedFamily) {
            publishedFamily
        } else null
}
