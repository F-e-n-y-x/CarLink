package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.WirelessStartupTimeline
import org.junit.Assert.*
import org.junit.Test

// CarLink: tests for P2pStableIdentity.kt.
class P2pStableIdentityTest {
    private val prefix = P2pOwnership.prefix("com.carlink.app", "device-user-signing-key-id")
    private var draws = 0
    private fun passphrase() = "Passphrase${++draws}xyzA"

    private fun decide(stored: P2pIdentity?, carName: String? = "CarLink", enabled: Boolean = true, sdk: Int = 33) =
        P2pStableIdentityPolicy.decide(enabled, sdk, stored, prefix, carName, ::passphrase)

    @Test fun firstUseDrawsAPairInTheOwnershipNamespaceAndStoresIt() {
        val choice = decide(null)
        assertEquals(P2pIdentityOutcome.NEW, choice.outcome)
        assertEquals("first_use", choice.reason)
        assertEquals(prefix + "CarLink", choice.identity!!.ssid)
        assertEquals(choice.identity, choice.toStore)
        assertTrue(P2pStableIdentityPolicy.isValidPassphrase(choice.identity!!.passphrase))
    }

    @Test fun storedPairIsKeptAsIs() {
        val stored = decide(null).toStore!!
        val choice = decide(stored)
        assertEquals(P2pIdentityOutcome.KEPT, choice.outcome)
        assertEquals(stored, choice.identity)
        assertNull(choice.toStore)
        assertEquals(1, draws)
    }

    @Test fun renamedCarOrUnusablePassphraseReplacesNameAndPassphraseTogether() {
        val stored = decide(null).toStore!!
        val renamed = decide(stored, carName = "Tang")
        assertEquals("name_changed", renamed.reason)
        assertEquals(prefix + "Tang", renamed.identity!!.ssid)
        assertNotEquals(stored.passphrase, renamed.identity!!.passphrase)
        assertEquals(renamed.identity, renamed.toStore)

        val broken = decide(P2pIdentity(stored.ssid, "short"))
        assertEquals("stored_unusable", broken.reason)
        assertEquals(P2pIdentityOutcome.NEW, broken.outcome)
    }

    @Test fun offAndOldAndroidKeepTheRandomPair() {
        assertNull(decide(null, enabled = false).identity)
        assertEquals(P2pIdentityOutcome.OFF, decide(null, enabled = false).outcome)
        assertNull(decide(null, sdk = 28).identity)
        assertEquals(P2pIdentityOutcome.SYSTEM, decide(null, sdk = 28).outcome)
        assertEquals(0, draws)
    }

    @Test fun nameIsAsciiAlphanumericAndFitsThirtyTwoBytes() {
        assertEquals(22, prefix.length)
        // 10 characters of room after the prefix.
        assertEquals(prefix + "TangEVHono", P2pStableIdentityPolicy.networkName(prefix, "Tang EV Honor"))
        val long = P2pStableIdentityPolicy.networkName(prefix, "Ünïcødé car name that is long")
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 32)
        assertTrue(long.removePrefix(prefix).all { it.code < 128 && it.isLetterOrDigit() })
        assertEquals(prefix + "CarPlay", P2pStableIdentityPolicy.networkName(prefix, "ü é"))
        assertEquals(prefix + "CarPlay", P2pStableIdentityPolicy.networkName(prefix, null))
        // WifiP2pConfig.Builder.setNetworkName: DIRECT- and two alphanumerics.
        assertTrue(P2pStableIdentityPolicy.networkName(prefix, "CarLink").matches(Regex("^DIRECT-[a-zA-Z0-9]{2}.*")))
    }

    @Test fun nameStaysRecognisableByDiPlayAndCarLinkButNeverByAndroidAuto() {
        val name = P2pStableIdentityPolicy.networkName(prefix, "CarLink")
        // DiPlay's reclaim check, even after a reinstall lost the recorded name.
        assertTrue(P2pOwnership.canReclaim(true, name, null, prefix))
        assertFalse(P2pOwnership.canReclaim(false, name, null, prefix))
        assertFalse(P2pOwnership.canReclaim(true, name, null, P2pOwnership.prefix("com.carlink.app", "other-device")))
        // CarLink's LeftoverGroupPolicy.CARPLAY_GROUP (app/…/aa/AaStartPolicy.kt).
        assertTrue(name.matches(Regex("^DIRECT-dp[0-9a-f]{12}-.*")))
        // DiAuto names its group DIRECT-<two of A-Z, 0-9>-<car>: never "dp".
        assertFalse(name.matches(Regex("^DIRECT-[A-Z0-9]{2}-.*")))
        assertFalse(P2pStableIdentityPolicy.isNamespaceName("DIRECT-Y1-CarLink", prefix))
        assertFalse(P2pStableIdentityPolicy.isNamespaceName(prefix, prefix))
        assertFalse(P2pStableIdentityPolicy.isNamespaceName(prefix + "Car-Link", prefix))
    }

    @Test fun liveGroupIsReadOnlyWhenOwnedWithTheWholePairOnAFittingChannel() {
        val identity = P2pIdentity(prefix + "CarLink", "Passphrase1xyzA")
        fun reads(owner: Boolean = true, name: String? = identity.ssid, pass: String? = identity.passphrase,
                  frequency: Int = 5180, fits: (Int) -> Boolean = { true }) =
            P2pStableIdentityPolicy.readsLiveGroup(identity, owner, name, pass, frequency, fits)
        assertTrue(reads())
        assertFalse(reads(owner = false))
        assertFalse(reads(name = prefix + "aB23"))
        assertFalse(reads(pass = "another-passphrase"))
        assertFalse(reads(pass = null))
        assertFalse(reads(frequency = 0))
        assertFalse(reads(fits = { it == 2437 }))
        assertFalse(P2pStableIdentityPolicy.readsLiveGroup(null, true, identity.ssid, identity.passphrase, 5180) { true })
    }

    @Test fun fittingChannelFollowsTheFirstPlannedRequest() {
        fun fits(request: P2pCreationRequest, frequency: Int) = P2pStableIdentityPolicy.fitsFirstRequest(request, frequency)
        assertTrue(fits(P2pCreationRequest(P2pCreationMode.ALIGNED_5_GHZ, 5180), 5180))
        assertFalse(fits(P2pCreationRequest(P2pCreationMode.ALIGNED_5_GHZ, 5180), 5745))
        assertTrue(fits(P2pCreationRequest(P2pCreationMode.BAND_5_GHZ), 5745))
        assertFalse(fits(P2pCreationRequest(P2pCreationMode.BAND_5_GHZ), 2437))
        assertTrue(fits(P2pCreationRequest(P2pCreationMode.BAND_2_GHZ), 2412))
        assertTrue(fits(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT), 2462))
        // Beside a station on 5200 MHz the plan asks for 5200 first: a live group on 5745 would make
        // the shared radio hop (PR #309), so it is not read.
        val first = P2pStartupRecovery.plan(5200).first()
        assertFalse(fits(first, 5745))
        assertTrue(fits(first, first.frequencyMHz!!))
        // A manual channel is the only fit.
        val manual = P2pStartupRecovery.plan(5180, preferredChannel = 149).first()
        assertTrue(fits(manual, 5745))
        assertFalse(fits(manual, 5180))
    }

    @Test fun passphraseNeverPrints() {
        assertFalse(P2pIdentity("DIRECT-dpX-CarLink", "secret-value").toString().contains("secret-value"))
    }

    @Test fun bssidHashIsShortStableAndHidesTheAddress() {
        val hash = WirelessStartupTimeline.shortHash("AA:BB:CC:DD:EE:FF")
        assertEquals(6, hash.length)
        assertEquals(hash, WirelessStartupTimeline.shortHash("aa:bb:cc:dd:ee:ff"))
        assertNotEquals(hash, WirelessStartupTimeline.shortHash("aa:bb:cc:dd:ee:fe"))
        assertEquals("unknown", WirelessStartupTimeline.shortHash(null))
        assertEquals("unknown", WirelessStartupTimeline.shortHash("02:00:00:00:00:00"))
    }
}
