package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairVerifyTest {
    private val controllerKeys = AirPlayCrypto.ed25519Generate()
    private val controllerId = "controller-under-test"

    private fun verifier() = PairVerify(
        AirPlayIdentity.generate(),
        PairingStore().apply { save(controllerId, controllerKeys.publicKey) },
    )

    private fun tlv(vararg items: Pair<Int, ByteArray>) = Tlv8Codec.encode(items.map { Tlv8Item(it.first, it.second) })

    private class Handshake(val ephemeral: AirPlayCrypto.X25519KeyPair, val accessoryEphemeral: ByteArray)

    private fun m1(verifier: PairVerify): Handshake {
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = Tlv8Codec.decode(verifier.handle(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey)))
        return Handshake(ephemeral, m2.getValue(3))
    }

    private fun m3(verifier: PairVerify, handshake: Handshake, signingKey: ByteArray = controllerKeys.privateKey): ByteArray {
        val shared = AirPlayCrypto.x25519Shared(handshake.ephemeral.privateKey, handshake.accessoryEphemeral)
        val encryptionKey = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
        val id = controllerId.toByteArray()
        val signature = AirPlayCrypto.ed25519Sign(signingKey,
            concatBytes(handshake.ephemeral.publicKey, id, handshake.accessoryEphemeral))
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 0x0a to signature))
        return verifier.handle(tlv(6 to byteArrayOf(3), 5 to sealed))
    }

    @Test fun theSharedSecretStaysHiddenUntilTheControllerProvesItsKey() {
        val verifier = verifier()
        val handshake = m1(verifier)
        assertNull("the secret is derived from a key anyone can send", verifier.shared)

        m3(verifier, handshake)

        assertTrue(verifier.isVerified)
        assertNotNull(verifier.shared)
    }

    @Test fun aThirdMessageSignedWithAnotherKeyLeavesTheSecretAndTheKeysHidden() {
        val verifier = verifier()
        val handshake = m1(verifier)

        m3(verifier, handshake, signingKey = AirPlayCrypto.ed25519Generate().privateKey)

        assertFalse(verifier.isVerified)
        assertNull(verifier.shared)
        assertNull(verifier.controlKeys)
    }

    @Test fun aThirdMessageBeforeAnyFirstMessageIsRefused() {
        val verifier = verifier()
        val unusedHandshake = Handshake(AirPlayCrypto.x25519Generate(), AirPlayCrypto.x25519Generate().publicKey)

        m3(verifier, unusedHandshake)

        assertFalse(verifier.isVerified)
        assertNull(verifier.shared)
        assertNull(verifier.controlKeys)
    }

    @Test fun aSecondFirstMessageAfterVerificationDoesNotReplaceTheSecret() {
        val verifier = verifier()
        m3(verifier, m1(verifier))
        val secret = verifier.shared!!.copyOf()

        m1(verifier)

        assertArrayEquals(secret, verifier.shared)
    }
}
