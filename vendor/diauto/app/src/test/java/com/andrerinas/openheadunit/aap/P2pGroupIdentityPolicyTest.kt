package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** CarLink: the kept Wi-Fi Direct identity ported from open-headunit PR #1006. */
class P2pGroupIdentityPolicyTest {

    @Test
    fun `the first bring-up draws a pair named after the car and stores it`() {
        val choice = P2pGroupIdentityPolicy.decide(keepIdentity = true, stored = null, deviceName = "My Car 2", random = Random(1))

        assertTrue(choice.identity.networkName.matches(Regex("^DIRECT-[A-Z0-9]{2}-MyCar2$")))
        assertEquals(12, choice.identity.passphrase.length)
        assertTrue(choice.identity.persistent)
        assertEquals(StoredP2pIdentity(choice.identity.networkName, choice.identity.passphrase), choice.toStore)
    }

    @Test
    fun `a kept pair is asked for again unchanged`() {
        val stored = StoredP2pIdentity("DIRECT-K7-CarLink", "Abcdefgh1234")

        val choice = P2pGroupIdentityPolicy.decide(true, stored, "CarLink")

        assertEquals(P2pGroupIdentity("DIRECT-K7-CarLink", "Abcdefgh1234", persistent = true), choice.identity)
        assertNull(choice.toStore)
    }

    @Test
    fun `a renamed car draws a new name and a new passphrase together`() {
        val stored = StoredP2pIdentity("DIRECT-K7-CarLink", "Abcdefgh1234")

        val choice = P2pGroupIdentityPolicy.decide(true, stored, "Octavia", random = Random(2))

        assertTrue(choice.identity.networkName.endsWith("-Octavia"))
        assertNotEquals(stored.passphrase, choice.identity.passphrase)
        assertEquals(choice.identity.networkName, choice.toStore?.networkName)
    }

    @Test
    fun `an unusable stored pair is replaced`() {
        val choice = P2pGroupIdentityPolicy.decide(true, StoredP2pIdentity("MyNet", "short"), "CarLink", random = Random(3))

        assertTrue(P2pGroupIdentityPolicy.isValid(choice.toStore!!))
    }

    @Test
    fun `without a kept identity every create is new and nothing is stored`() {
        val choice = P2pGroupIdentityPolicy.decide(false, StoredP2pIdentity("DIRECT-K7-CarLink", "Abcdefgh1234"), "CarLink")

        assertFalse(choice.identity.persistent)
        assertNull(choice.toStore)
    }

    @Test
    fun `names keep the platform's shape and length`() {
        val name = P2pGroupIdentityPolicy.networkName("AB", "Ünïcödé Very Long Car Name Over Twenty")
        assertTrue(P2pGroupIdentityPolicy.isValidName(name))
        assertTrue(name.toByteArray().size <= 32)
        assertEquals("DIRECT-AB-HeadUnit", P2pGroupIdentityPolicy.networkName("AB", "  !! "))
    }

    @Test
    fun `a live group is read only when its whole pair matches`() {
        fun reads(sdk: Int, owner: Boolean, name: String?, pass: String?, owned: Boolean = false) =
            P2pGroupIdentityPolicy.readsExistingGroup(sdk, owner, name, "DIRECT-K7-CarLink", pass, "Abcdefgh1234", owned)

        assertTrue(reads(34, true, "DIRECT-K7-CarLink", "Abcdefgh1234"))
        assertFalse("old passphrase", reads(34, true, "DIRECT-K7-CarLink", "Old12345678"))
        assertFalse("other name", reads(34, true, "DIRECT-dpabc-1234", "Abcdefgh1234"))
        assertFalse("not the owner", reads(34, false, "DIRECT-K7-CarLink", "Abcdefgh1234"))
        // Below API 29 the platform names the group: only a group this engine recorded is read.
        assertTrue(reads(28, true, "DIRECT-xy-Android_1234", "zzzzzzzz", owned = true))
        assertFalse(reads(28, true, "DIRECT-xy-Android_1234", "zzzzzzzz", owned = false))
    }
}
