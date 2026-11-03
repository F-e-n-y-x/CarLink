// CarLink: new file (see docs/VENDOR_CHANGES.md). Wireless CarPlay's iAP2 Bluetooth link carried by
// a stereo's external Bluetooth module, through the ZLink-type daemon DiAuto's ZBT route also uses.
package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Whether this session takes the iPhone's iAP2 link from the stereo's Bluetooth module instead of
 * Android's Bluetooth. Set by the host before each session (CarLink's DeviceEnvironment).
 */
object ModuleIap2Route {
    @Volatile
    var enabled: Boolean = false

    const val HOST = "127.0.0.1"
    const val PORT = 3152

    /**
     * The daemon's link type asked for. It accepts 1, 2, 3 and 8 and 2 is Android Auto (DiAuto
     * ZbtMessages); 1 for CarPlay is a hypothesis, so far verified only against an emulated daemon.
     */
    const val ENABLE_TYPE_CARPLAY = 1

    /**
     * How long the module may take to report the iPhone: it pages the phone itself, and a cold page
     * took 25 s against the emulated module (2026-11-04), past DiPlay's own 15 s RFCOMM deadline.
     */
    const val CONNECT_TIMEOUT_MILLIS = 30_000L

    /** The iPhone the module last reported connected (address with colons, name), for the host's phone list. */
    @Volatile
    var lastPhone: Pair<String, String?>? = null
}

/**
 * One session's link: [open] asks the daemon to carry CarPlay and to reach the phone,
 * [awaitPhone] blocks until the daemon reports it connected, then [access] carries the raw
 * RFCOMM bytes (message 0x105 both ways) for [BluetoothRfcommDuplexStream].
 *
 * Framing: a 16-byte big-endian header (magic 0x0000FFFF, version 0x101, id, body length) and a
 * proto2 body of scalar fields, field 1 the id, as DiAuto's ZbtFraming/ZbtMessages describe.
 */
class ZbtIap2Link private constructor(private val socket: Socket) : Closeable {
    @Volatile var moduleMac: String? = null
        private set
    @Volatile var phoneMac: String? = null
        private set
    @Volatile var phoneName: String? = null
        private set

    private val phonePresent = CountDownLatch(1)
    private val answered = CountDownLatch(1)
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val writeLock = Any()
    @Volatile private var closed = false

    private val reader = Thread({ readLoop() }, "zbt-iap2-reader").apply { isDaemon = true }

    /**
     * Blocks until the module reports the phone connected; false on timeout or a closed channel.
     * Repeats `RequestReconn` every [rewakeMillis] (at most [MAX_REWAKES] times) while it waits.
     */
    fun awaitPhone(timeoutMillis: Long, rewakeMillis: Long = REWAKE_MILLIS): Boolean {
        // The daemon serves one client and silently ignores a second (ZLink's own app, or Android
        // Auto's carrier still holding it): no InitInfo soon means busy, not a slow phone.
        if (!answered.await(ANSWER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw IOException("the stereo's Bluetooth module did not answer; another app may be using it")
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var rewakes = 0
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) return false
            val slice = if (rewakes < MAX_REWAKES) minOf(left, rewakeMillis) else left
            if (phonePresent.await(slice, TimeUnit.MILLISECONDS)) break
            // A phone that ignored the first request was otherwise asked again only by a whole retry.
            if (rewakes < MAX_REWAKES && slice < left) {
                rewakes++
                runCatching { sendReconnect() }
            }
        }
        if (closed) throw IOException("the module daemon closed the CarPlay channel")
        return true
    }

    /** The module's Bluetooth address with colons, as iAP2 identification announces it. */
    fun moduleMacWithColons(): String? = moduleMac?.let(::withColons)

    internal val access: BluetoothRfcommSocketAccess = object : BluetoothRfcommSocketAccess {
        override fun inputStream(): InputStream = input
        override fun outputStream(): OutputStream = output
        override fun close() = this@ZbtIap2Link.close()
    }

    private val input = object : InputStream() {
        private var chunk: ByteArray = ByteArray(0)
        private var at = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at >= chunk.size) {
                if (closed && incoming.isEmpty()) return -1
                chunk = incoming.poll(200, TimeUnit.MILLISECONDS) ?: continue
                at = 0
            }
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            while (at < off + len) {
                val n = minOf(MAX_CHUNK, off + len - at)
                send(RFCOMM_DATA, b.copyOfRange(at, at + n))
                at += n
            }
        }
    }

    private fun send(msgId: Int, body: ByteArray) = synchronized(writeLock) {
        if (closed) throw IOException("the module CarPlay channel is closed")
        val out = socket.getOutputStream()
        out.write(header(msgId, body.size) + body)
        out.flush()
    }

    private fun sendReconnect() = send(REQUEST_RECONN,
        varintField(1, REQUEST_RECONN) + varintField(2, 1) + varintField(3, ModuleIap2Route.ENABLE_TYPE_CARPLAY))

    private fun readLoop() {
        try {
            val data = DataInputStream(socket.getInputStream())
            val header = ByteArray(HEADER_SIZE)
            while (!closed) {
                data.readFully(header)
                val magic = int(header, 0)
                val msgId = int(header, 8)
                val size = int(header, 12)
                if (magic != MAGIC || size < 0 || size > MAX_BODY) throw IOException("ZBT frame sync lost (magic=$magic size=$size)")
                val body = ByteArray(size).also { data.readFully(it) }
                when (msgId) {
                    RFCOMM_DATA -> if (body.isNotEmpty()) incoming.put(body)
                    INIT_INFO -> {
                        parse(body)?.strings?.get(4)?.let { moduleMac = it }
                        answered.countDown()
                    }
                    LINK_INFO2 -> parse(body)?.let { fields ->
                        fields.strings[7]?.takeIf { it.isNotEmpty() }?.let { phoneMac = it }
                        fields.strings[8]?.takeIf { it.isNotEmpty() }?.let { phoneName = it }
                        // Only an iPhone link counts: the same module also reports Android phones (phone_type 1, AA).
                        if (fields.varints[3] == 1L && fields.varints[2] != PHONE_TYPE_ANDROID_AUTO) {
                            phoneMac?.let { ModuleIap2Route.lastPhone = withColons(it) to phoneName }
                            phonePresent.countDown()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Closed by us, or the daemon went away: the stream reports end-of-input.
        } finally {
            closed = true
            answered.countDown()
            phonePresent.countDown()
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    internal class Fields(val varints: Map<Int, Long>, val strings: Map<Int, String>)

    companion object {
        const val MAGIC = 0x0000FFFF
        private const val VERSION = 0x00000101
        const val HEADER_SIZE = 16
        const val REQUEST_INIT = 0x101
        const val INIT_INFO = 0x102
        const val RFCOMM_DATA = 0x105
        const val LINK_INFO2 = 0x10C
        const val REQUEST_RECONN = 0x114
        private const val MAX_CHUNK = 960
        private const val MAX_BODY = 1 shl 20
        private const val CONNECT_TIMEOUT_MILLIS = 4_000
        private const val ANSWER_TIMEOUT_MILLIS = 3_000L
        /** Longer than a Bluetooth page (5.12 s), so a repeat never lands on a page in progress (DiAuto ZbtWakePolicy). */
        const val REWAKE_MILLIS = 6_000L
        const val MAX_REWAKES = 3
        /** LinkInfo phone_type of an Android Auto phone (DiAuto ZbtPolicies.PHONE_TYPE_ANDROID_AUTO). */
        const val PHONE_TYPE_ANDROID_AUTO = 1L

        /** Opens the daemon channel for CarPlay and asks the module to reach the phone. */
        fun open(host: String = ModuleIap2Route.HOST, port: Int = ModuleIap2Route.PORT): ZbtIap2Link {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                socket.tcpNoDelay = true
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw IOException("no Bluetooth module daemon on $host:$port: ${e.message}", e)
            }
            val link = ZbtIap2Link(socket)
            link.reader.start()
            try {
                val type = ModuleIap2Route.ENABLE_TYPE_CARPLAY
                link.send(REQUEST_INIT, varintField(1, 1) + varintField(2, type))
                link.sendReconnect()
            } catch (e: IOException) {
                link.close()
                throw e
            }
            return link
        }

        fun header(msgId: Int, bodySize: Int): ByteArray = ByteArray(HEADER_SIZE).also {
            put(it, 0, MAGIC); put(it, 4, VERSION); put(it, 8, msgId); put(it, 12, bodySize)
        }

        fun varintField(field: Int, value: Int): ByteArray = varint((field shl 3).toLong()) + varint(value.toLong() and 0xFFFFFFFFL)

        private fun varint(value: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            do {
                var b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) b = b or 0x80
                out.write(b)
            } while (v != 0L)
            return out.toByteArray()
        }

        /** Scalar proto2 fields (varints and length-delimited strings) by number, or null if malformed. */
        internal fun parse(body: ByteArray): Fields? {
            val varints = HashMap<Int, Long>()
            val strings = HashMap<Int, String>()
            var i = 0
            fun readVarint(): Long? {
                var result = 0L
                var shift = 0
                while (i < body.size && shift <= 63) {
                    val b = body[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
                return null
            }
            while (i < body.size) {
                val tag = readVarint() ?: return null
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> varints[field] = readVarint() ?: return null
                    2 -> {
                        val len = readVarint() ?: return null
                        if (len < 0 || len > body.size - i) return null
                        strings[field] = String(body, i, len.toInt(), Charsets.UTF_8)
                        i += len.toInt()
                    }
                    else -> return null
                }
            }
            return Fields(varints, strings)
        }

        /** "E848B8C82000" → "E8:48:B8:C8:20:00"; an address that already has colons is kept. */
        fun withColons(mac: String): String =
            if (mac.length == 12 && ':' !in mac) mac.chunked(2).joinToString(":").uppercase() else mac.uppercase()

        private fun put(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
        }

        private fun int(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }
}
