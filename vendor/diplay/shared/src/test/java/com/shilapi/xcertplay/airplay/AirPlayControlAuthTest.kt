package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The control connection runs over a real loopback socket, as an iPhone's does. A peer that has not
 * finished pair-verify has no control cipher, and must not reach SETUP, RECORD, TEARDOWN or /command.
 *
 * The request order of a real iPhone (pair-setup, pair-verify M1/M3, then only encrypted requests) was
 * captured on a head unit; `positiveControl` follows that order.
 */
@RunWith(RobolectricTestRunner::class) // AirPlaySession logs through android.util.Log.
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayControlAuthTest {
    private val screenSetups = AtomicInteger()
    private val audioSetups = AtomicInteger()
    private val activations = AtomicInteger()
    private val commands = AtomicInteger()
    private val teardowns = AtomicInteger()

    private lateinit var server: ServerSocket
    private lateinit var client: Socket
    private lateinit var session: AirPlaySession
    private val controllerKeys = AirPlayCrypto.ed25519Generate()
    private val controllerId = "controller-under-test"
    private var clientCipher: ControlCipher? = null
    private var cseq = 0

    @Before fun connect() {
        server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        client = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 5_000 }
        val accepted = server.accept()
        val pairings = PairingStore().apply { save(controllerId, controllerKeys.publicKey) }
        session = AirPlaySession(
            socket = accepted,
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                main = AirPlayDisplayConfig(1280, 720)),
            identity = AirPlayIdentity.generate(),
            pairings = pairings,
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { activations.incrementAndGet() }
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                    commands.incrementAndGet()
                }
            },
            media = object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
                    screenSetups.incrementAndGet()
                    return 43210
                }
                override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
                    audioSetups.incrementAndGet()
                    return linkedMapOf("type" to type, "dataPort" to 43211, "controlPort" to 43212)
                }
                override fun onTeardown(session: AirPlaySession, type: Int) { teardowns.incrementAndGet() }
            },
        )
        session.start()
    }

    @After fun disconnect() {
        session.close()
        client.close()
        server.close()
    }

    private class Reply(val status: Int, val body: ByteArray)

    private fun request(method: String, path: String, body: ByteArray = ByteArray(0), contentType: String? = null): ByteArray {
        val head = buildString {
            append(method).append(' ').append(path).append(" RTSP/1.0\r\n")
            append("CSeq: ").append(cseq++).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + body
    }

    private fun exchange(wire: ByteArray): Reply {
        client.getOutputStream().write(clientCipher?.encrypt(wire) ?: wire)
        client.getOutputStream().flush()
        return readReply()
    }

    private fun readReply(): Reply {
        val raw = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        val input = client.getInputStream()
        var pending = ByteArray(0)
        while (true) {
            val count = input.read(chunk)
            check(count >= 0) { "connection closed before a full reply" }
            val cipher = clientCipher
            if (cipher != null) {
                pending += chunk.copyOf(count)
                val decrypted = cipher.decrypt(pending)
                pending = decrypted.rest
                raw.write(decrypted.data)
            } else {
                raw.write(chunk, 0, count)
            }
            val bytes = raw.toByteArray()
            val end = indexOf(bytes, "\r\n\r\n".toByteArray())
            if (end < 0) continue
            val head = String(bytes, 0, end, Charsets.US_ASCII)
            val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            if (bytes.size < end + 4 + length) continue
            val status = head.lineSequence().first().split(' ')[1].toInt()
            return Reply(status, bytes.copyOfRange(end + 4, end + 4 + length))
        }
    }

    private fun indexOf(source: ByteArray, target: ByteArray): Int {
        for (index in 0..source.size - target.size) {
            if (target.indices.all { source[index + it] == target[it] }) return index
        }
        return -1
    }

    private fun tlv(vararg items: Pair<Int, ByteArray>) =
        Tlv8Codec.encode(items.map { Tlv8Item(it.first, it.second) })

    private fun setup(streams: List<Map<String, Any?>>) =
        request("SETUP", "rtsp://127.0.0.1/1", BplistCodec.encode(mapOf("streams" to streams)),
            "application/x-apple-binary-plist")

    private val screenStream = mapOf("type" to 110)
    private val microphoneStream = mapOf("type" to 100, "audioType" to "speechrecognition", "dataPort" to 40000)

    /** Pair-verify as the iPhone does it: M1, M3. Returns once the accessory has answered M3. */
    private fun pairVerify() {
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = exchange(request("POST", "/pair-verify", tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey),
            "application/octet-stream"))
        assertEquals(200, m2.status)
        val reply = Tlv8Codec.decode(m2.body)
        val accessoryEphemeral = reply.getValue(3)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, accessoryEphemeral)
        val encryptionKey = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
        val id = controllerId.toByteArray()
        val signature = AirPlayCrypto.ed25519Sign(controllerKeys.privateKey, concatBytes(ephemeral.publicKey, id, accessoryEphemeral))
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"),
            tlv(1 to id, 0x0a to signature))
        val m4 = exchange(request("POST", "/pair-verify", tlv(6 to byteArrayOf(3), 5 to sealed), "application/octet-stream"))
        assertEquals(200, m4.status)
        assertEquals(4, Tlv8Codec.decode(m4.body).getValue(6).single().toInt())
        clientCipher = ControlCipher(
            AirPlayCrypto.hkdfSha512(shared, "Control-Salt".toByteArray(), "Control-Read-Encryption-Key".toByteArray()),
            AirPlayCrypto.hkdfSha512(shared, "Control-Salt".toByteArray(), "Control-Write-Encryption-Key".toByteArray()),
        )
    }

    /** An attacker's M1 with its own key: the accessory answers, and derives a secret the attacker also knows. */
    private fun attackerStartsPairVerify() {
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = exchange(request("POST", "/pair-verify", tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey),
            "application/octet-stream"))
        assertEquals(200, m2.status)
    }

    @Test fun aThirdPairVerifyMessageSignedWithAnotherKeyDoesNotUnlockSetup() {
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = exchange(request("POST", "/pair-verify", tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey),
            "application/octet-stream"))
        val accessoryEphemeral = Tlv8Codec.decode(m2.body).getValue(3)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, accessoryEphemeral)
        val encryptionKey = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
        val id = controllerId.toByteArray()
        val forged = AirPlayCrypto.ed25519Sign(AirPlayCrypto.ed25519Generate().privateKey,
            concatBytes(ephemeral.publicKey, id, accessoryEphemeral))
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 0x0a to forged))
        val m4 = exchange(request("POST", "/pair-verify", tlv(6 to byteArrayOf(3), 5 to sealed), "application/octet-stream"))
        assertTrue("the accessory must report an error", Tlv8Codec.decode(m4.body).containsKey(7))

        val reply = exchange(setup(listOf(screenStream)))

        assertEquals(470, reply.status)
        assertEquals(0, screenSetups.get())
    }

    @Test fun plaintextSetupBeforePairVerifyIsRefused() {
        val reply = exchange(setup(listOf(screenStream)))
        assertEquals(470, reply.status)
        assertEquals("a screen stream was set up for a peer that never proved its key", 0, screenSetups.get())
    }

    @Test fun plaintextSetupAfterOnlyTheFirstPairVerifyMessageIsRefused() {
        attackerStartsPairVerify()
        val reply = exchange(setup(listOf(screenStream)))
        assertEquals(470, reply.status)
        assertEquals(0, screenSetups.get())
    }

    @Test fun plaintextMicrophoneStreamBeforePairVerifyIsRefused() {
        val reply = exchange(setup(listOf(microphoneStream)))
        assertEquals(470, reply.status)
        assertEquals("an audio stream was set up for a peer that never proved its key", 0, audioSetups.get())
    }

    @Test fun plaintextRecordBeforePairVerifyDoesNotMakeTheSessionActive() {
        val reply = exchange(request("RECORD", "rtsp://127.0.0.1/1"))
        assertEquals(470, reply.status)
        assertEquals(0, activations.get())
    }

    @Test fun plaintextCommandAndTeardownBeforePairVerifyAreRefused() {
        val command = exchange(request("POST", "/command",
            BplistCodec.encode(mapOf("type" to "requestUI", "params" to mapOf<String, Any?>())),
            "application/x-apple-binary-plist"))
        assertEquals(470, command.status)
        assertEquals(0, commands.get())
        val teardown = exchange(request("TEARDOWN", "rtsp://127.0.0.1/1", BplistCodec.encode(mapOf<String, Any?>()),
            "application/x-apple-binary-plist"))
        assertEquals(470, teardown.status)
    }

    @Test fun infoAndThePairingRoutesStillAnswerBeforePairVerify() {
        assertEquals(200, exchange(request("GET", "/info")).status)
        attackerStartsPairVerify() // /pair-verify answered above with 200
    }

    @Test fun positiveControlAPairedControllerCanSetUpAfterPairVerify() {
        pairVerify()
        val reply = exchange(setup(listOf(screenStream)))
        assertEquals(200, reply.status)
        assertEquals(1, screenSetups.get())
        val record = exchange(request("RECORD", "rtsp://127.0.0.1/1"))
        assertEquals(200, record.status)
        assertEquals(1, activations.get())
        val info = exchange(request("GET", "/info"))
        assertEquals(200, info.status)
        assertTrue(info.body.isNotEmpty())
        assertNotNull(clientCipher)
    }
}
