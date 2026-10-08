package com.andrerinas.openheadunit.aap

object LocalHotspotPolicy {
    fun preferredFiveGhzChannel(stationFrequency: Int?): Int =
        if (stationFrequency != null && stationFrequency in 5160..5895 && (stationFrequency - 5000) % 5 == 0)
            (stationFrequency - 5000) / 5 else 36

    /** Refuse ambiguous candidates rather than advertise a station/cellular address to the phone. */
    fun pick(
        candidates: List<ApInterfaceCandidate>,
        previousAddresses: Set<String>,
        upstreamInterfaces: Set<String> = emptySet(),
    ): ApInterfaceCandidate? =
        candidates.filter {
            it.isUp && !it.isLoopback && it.siteLocalIpv4 != null &&
                it.siteLocalIpv4 !in previousAddresses &&
                it.name !in upstreamInterfaces &&
                it.name.matches(Regex("(?:ap|wlan|swlan|softap)[0-9]+"))
        }.singleOrNull()

    fun eui64Mac(bytes: ByteArray): String? {
        if (bytes.size != 16 || bytes[0] != 0xfe.toByte() || bytes[1].toInt() and 0xc0 != 0x80 ||
            bytes[11] != 0xff.toByte() || bytes[12] != 0xfe.toByte()) return null
        val mac = byteArrayOf((bytes[8].toInt() xor 2).toByte(), bytes[9], bytes[10], bytes[13], bytes[14], bytes[15])
            .joinToString(":") { "%02x".format(it.toInt() and 255) }
        return mac.takeIf(NativeCredentialsPolicy::isUsableBssid)
    }
}
