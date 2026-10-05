package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BufferedAudioSessionTest {
    @Test
    fun disabledOutputDeclinesType103BeforeCallingTheMediaHandler() {
        var setups = 0
        val session = session(disabled = true, media = object : AirPlayMediaHandler {
            override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
                setups++
                return mapOf("type" to 103, "dataPort" to 1234)
            }
        })
        try {
            val response = handle(session, "SETUP", BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 103)))))
            val decoded = BplistCodec.decode(response.body) as Map<*, *>
            assertEquals(emptyList<Any>(), decoded["streams"])
            assertEquals(0, setups)
        } finally { session.close() }
    }

    @Test
    fun malformedControlIsRejectedBeforeItCanDefaultToStartingPlayback() {
        var controls = 0
        val session = session(media = object : AirPlayMediaHandler {
            override fun onBufferedAudioControl(session: AirPlaySession, method: String, body: Map<String, Any?>): Map<String, Any?>? {
                controls++
                return null
            }
        })
        try {
            assertEquals(400, handle(session, "SETRATE", byteArrayOf(1, 2, 3)).status)
            assertEquals(0, controls)
            assertEquals(200, handle(session, "GETANCHOR", ByteArray(0)).status)
            assertEquals(1, controls)
        } finally { session.close() }
    }

    @Test
    fun rejectedReplacementPreservesTheExistingBufferedStream() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            val first = engine.onBufferedAudio(session, validSetup())!!
            assertNull(engine.onBufferedAudio(session, validSetup() + ("shk" to ByteArray(8))))
            assertNull(engine.onBufferedAudio(session, validSetup() + ("spf" to 960)))
            Socket("127.0.0.1", first["dataPort"] as Int).use { }
            val feedback = engine.onFeedback(session)!!["streams"] as List<*>
            assertEquals(1, feedback.size)
            assertEquals(103, (feedback.single() as Map<*, *>)["type"])
        } finally { session.close() }
    }

    @Test
    fun missingOrInvalidRateCannotStartAValidStream() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(media = engine)
        try {
            assertTrue(engine.onBufferedAudio(session, validSetup()) != null)
            assertNull(engine.onBufferedAudioControl(session, "SETRATE", emptyMap()))
            assertNull(engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rate" to 2)))
            assertNull(engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap()))
            assertEquals(1, engine.onBufferedAudioControl(session, "SETRATE", mapOf("rate" to 1, "rtpTime" to 1024L))!!["rate"])
            assertNull(engine.onBufferedAudioControl(session, "SETRATEANCHORTIME", mapOf("rate" to 0.5)))
            assertEquals(1, engine.onBufferedAudioControl(session, "GETANCHOR", emptyMap())!!["rate"])
        } finally { session.close() }
    }

    private fun validSetup(): Map<String, Any?> = mapOf(
        "type" to 103, "shk" to ByteArray(32), "audioFormat" to 0x800000,
        "ct" to 4, "spf" to 1024, "streamConnectionID" to 1,
    )

    private fun handle(session: AirPlaySession, method: String, body: ByteArray): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, RtspMessage.Request(method, "rtsp://test", "RTSP/1.0", emptyMap(), body)) as RtspMessage.Response

    private fun session(disabled: Boolean = false, media: AirPlayMediaHandler): AirPlaySession = AirPlaySession(
        socket = object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
        },
        config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            mainBufferedAudio = true, disableAudioOutput = disabled,
        ),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = media,
    )
}
