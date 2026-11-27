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
     * The car hotspot (not the local-only AP) may still add IPv6 for a head unit that clearly runs it:
     * [CarHotspotIpFamily] decides per run, and every `dualStack` parameter below carries that decision.
     */
    fun ipv4Only(backend: WirelessHotspotBackend): Boolean =
        backend == WirelessHotspotBackend.MANUAL_HOTSPOT || backend == WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT

    /**
     * [addresses] this run may publish, listen on or offer: only the IPv4 ones on an [ipv4Only] backend,
     * unless the car hotspot's automatic choice ([CarHotspotIpFamily]) went dual stack, which also keeps
     * IPv6 addresses except a link-local one (see [withoutLinkLocalIpv6]).
     */
    fun usable(backend: WirelessHotspotBackend, addresses: List<java.net.InetAddress>, dualStack: Boolean = false): List<java.net.InetAddress> =
        when {
            !ipv4Only(backend) -> addresses
            dualStack -> withoutLinkLocalIpv6(addresses)
            else -> addresses.filterIsInstance<java.net.Inet4Address>()
        }

    /**
     * What the car-hotspot IP-family probe ([CarHotspotIpFamily.probe]) looks at: the AP interface's live
     * addresses with IPv6 included, or the published snapshot when the scan found none. It is deliberately
     * not passed through [usable]: that strips IPv6 from the advertised host list, and a probe fed that
     * list would never see IPv6 and always choose IPv4 only.
     */
    fun probeAddresses(scanned: List<java.net.InetAddress>, snapshot: List<java.net.InetAddress>): List<java.net.InetAddress> =
        scanned.ifEmpty { snapshot }

    /**
     * [addresses] a car-hotspot run may serve and advertise (Bonjour AAAA, 0x4301): never a link-local
     * IPv6 address, which stalled the CS75 Plus SoftAP (#93). Global/ULA IPv6 survives, but only a dual-stack
     * decision puts a listener on it, so IPv4-only runs still advertise A records only.
     */
    fun withoutLinkLocalIpv6(addresses: List<java.net.InetAddress>): List<java.net.InetAddress> =
        addresses.filterNot { it is java.net.Inet6Address && it.isLinkLocalAddress }

    /**
     * Whether the AirPlay media sockets (screen, NTP, event, keep-alive, audio) bind the control session's local
     * address instead of `::` when that address is IPv4 (`mediaBindAddress`; an IPv6 session always keeps `::`,
     * so this is the same for IPv4-only and dual-stack runs). True on every backend that puts the iPhone on an
     * ordinary IPv4 network: the car hotspot, LocalOnlyHotspot and existing Wi-Fi, which includes an iPhone's own
     * Personal Hotspot joined by the head unit. There an IPv4 session finishing SETUP and then stalling at
     * Picture is the symptom of #93: on a head unit whose `::` sockets are IPv6-only (Android's IPV6_V6ONLY) the
     * iPhone's IPv4 connects to the screen, timing and event ports are never accepted. Wi-Fi Direct is unchanged.
     */
    fun bindMediaToSessionIpv4(backend: WirelessHotspotBackend): Boolean =
        backend != WirelessHotspotBackend.WIFI_P2P

    /**
     * [info] without its IPv6 addresses on an [ipv4Only] backend (a null host is then found by the address
     * wait). On a [dualStack] car-hotspot run only the link-local IPv6 addresses are dropped, and the host
     * stays an IPv4 one: IPv4 is always published first.
     */
    fun forPath(info: WirelessHotspotInfo, dualStack: Boolean = false): WirelessHotspotInfo {
        if (!ipv4Only(info.backend)) return info
        val hosts = usable(info.backend, info.hostAddresses, dualStack)
        val host = info.hostAddress as? java.net.Inet4Address
            ?: hosts.filterIsInstance<java.net.Inet4Address>().firstOrNull()
            ?: hosts.firstOrNull()
        return WirelessHotspotInfo(
            info.ssid, info.passphrase, info.security, info.channel, info.frequencyMHz, info.bssid,
            info.interfaceName, host, info.bandLabel,
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
