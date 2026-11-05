// CarLink: new file (see docs/VENDOR_CHANGES.md). Wireless CarPlay's iAP2 Bluetooth link through an FYT
// stereo's Bluetooth module, over its /dev/rf_serial text protocol (design/EXTERNAL_BLUETOOTH.md).
package com.shilapi.xcertplay.transport

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The module pages and pairs the iPhone itself and carries the iAP2 RFCOMM channel as text lines:
 * the host sends `AT#<code><data>\r\n`, the module answers with lines that start with a two-letter
 * code. iAP2, MFi and the Wi-Fi hand-off stay DiPlay's own; the module only moves bytes.
 *
 * | Host → module | Meaning |
 * |---|---|
 * | `AT#VE`, `AT#QN`, `AT#QI` | module address (→ `DB`), name (→ `NA`), current state (→ phone events) |
 * | `AT#SP<phone 12 hex><iAP2 UUID 32 hex>` | open the iPhone's iAP2 channel (→ `SV`) |
 * | `AT#SG<HEX>` | send up to 100 bytes on the channel |
 * | `AT#SH` | close the channel |
 *
 * Module → host: `DB<mac>`, `IB`/`IA` phone connected/disconnected, `JH<mac>`, `SA<name>`,
 * `US`, `UU<uuid>`…, `UE` (the phone's services), `SV`/`SS` channel up/down, `SI<HEX>` bytes received.
 */
internal class FytRfSerialIap2Link(
    private val input: InputStream,
    private val output: OutputStream,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onClose: () -> Unit = {},
) : ModuleIap2Link {
    @Volatile var moduleMac: String? = null
        private set
    @Volatile var phoneMac: String? = null
        private set
    @Volatile override var phoneName: String? = null
        private set

    @Volatile private var phoneUp = false
    @Volatile private var phoneSeenAt = 0L
    @Volatile private var iap2Offered = false
    @Volatile private var channelUp = false
    @Volatile private var closed = false
    private val services = ArrayList<String>() // reader thread only
    private val changed = Object()
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val writeLock = Any()

    // shortcut: a read blocked on the device is not woken by close(), so this thread ends at the
    // module's next line after a session; upgrade to a non-blocking fd + poll if threads pile up.
    private val reader = Thread({ readLoop() }, "fyt-iap2-reader").apply { isDaemon = true }

    fun start() {
        reader.start()
        command("VE")
        command("QN")
        command("QI")
    }

    override fun awaitPhone(timeoutMillis: Long, rewakeMillis: Long): Boolean {
        val deadline = clock() + timeoutMillis
        var askedAt = clock()
        var dialedAt = 0L
        var dials = 0
        while (true) {
            if (channelUp) return true
            if (closed) throw IOException("the stereo's Bluetooth module port closed")
            val now = clock()
            if (now >= deadline) return false
            val mac = phoneMac
            // The service list may have gone by before this session opened the port; dial anyway after a grace.
            val ready = phoneUp && mac != null && (iap2Offered || now - phoneSeenAt >= SERVICES_GRACE_MILLIS)
            if (ready && dials < MAX_DIALS && (dials == 0 || now - dialedAt >= rewakeMillis)) {
                command("SP", mac + IAP2_UUID)
                dialedAt = now
                dials++
            } else if (!ready && now - askedAt >= rewakeMillis) {
                command("QI")
                askedAt = now
            }
            synchronized(changed) { if (!channelUp && !closed) changed.wait(minOf(POLL_MILLIS, deadline - now).coerceAtLeast(1)) }
        }
    }

    override fun moduleMacWithColons(): String? = moduleMac?.let(ZbtIap2Link::withColons)

    override val access: BluetoothRfcommSocketAccess = object : BluetoothRfcommSocketAccess {
        override fun inputStream(): InputStream = rfcommInput
        override fun outputStream(): OutputStream = rfcommOutput
        override fun close() = this@FytRfSerialIap2Link.close()
    }

    private val rfcommInput = object : InputStream() {
        private var chunk = ByteArray(0)
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

    private val rfcommOutput = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("the stereo's Bluetooth module channel is closed")
            var at = off
            while (at < off + len) {
                val n = minOf(MAX_CHUNK, off + len - at)
                command("SG", hex(b, at, n))
                at += n
            }
        }
    }

    private fun command(code: String, data: String = "") = synchronized(writeLock) {
        output.write("AT#$code$data\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun readLoop() {
        try {
            input.bufferedReader(Charsets.ISO_8859_1).use { lines ->
                while (!closed) {
                    val line = lines.readLine() ?: break // \r, \n and \r\n all end a line, as the module mixes them
                    if (line.length >= 2) handle(line)
                }
            }
        } catch (_: IOException) {
            // The port went away or was closed by us.
        } finally {
            closed = true
            signal()
        }
    }

    internal fun handle(line: String) {
        val data = line.substring(2).trim()
        when (line.substring(0, 2)) {
            "DB" -> moduleMac = data.uppercase()
            "IB" -> { phoneUp = true; phoneSeenAt = clock() }
            "IA" -> {
                phoneUp = false
                iap2Offered = false
                if (channelUp) { channelUp = false; closed = true }
            }
            "JH" -> { phoneMac = data.uppercase(); remember() }
            "SA" -> { phoneName = data.ifEmpty { null }; remember() }
            "US" -> services.clear()
            "UU" -> services += data
            "UE" -> iap2Offered = services.any { it.equals(IAP2_UUID, ignoreCase = true) }
            "SV" -> channelUp = true
            // The channel dropped: the stream ends, and DiPlay's own retry dials again.
            "SS" -> if (channelUp) { channelUp = false; closed = true }
            "SI" -> unhex(data)?.let { if (it.isNotEmpty()) incoming.put(it) }
        }
        signal()
    }

    private fun remember() {
        phoneMac?.let { ModuleIap2Route.lastPhone = ZbtIap2Link.withColons(it) to phoneName }
    }

    private fun signal() = synchronized(changed) { changed.notifyAll() }

    override fun close() {
        if (channelUp) runCatching { command("SH") }
        channelUp = false
        closed = true
        signal()
        runCatching { onClose() }
    }

    companion object {
        const val DEVICE = "/dev/rf_serial"
        /** The iPhone's iAP2 Bluetooth service (00000000-deca-fade-deca-deafdecacafe), as the module writes it. */
        const val IAP2_UUID = "00000000DECAFADEDECADEAFDECACAFE"
        /** Payload bytes per `AT#SG` line, as FYT's own CarPlay sends them. */
        const val MAX_CHUNK = 100
        const val MAX_DIALS = 3
        const val SERVICES_GRACE_MILLIS = 3_000L
        private const val POLL_MILLIS = 250L

        fun open(path: String = DEVICE): FytRfSerialIap2Link {
            val file = File(path)
            if (!file.exists()) throw IOException("the stereo's Bluetooth module port $path is missing")
            val input = try {
                FileInputStream(file)
            } catch (e: Exception) {
                throw IOException("the stereo's Bluetooth module port $path cannot be opened: ${e.message}", e)
            }
            val output = try {
                FileOutputStream(file)
            } catch (e: Exception) {
                runCatching { input.close() }
                throw IOException("the stereo's Bluetooth module port $path cannot be opened: ${e.message}", e)
            }
            return FytRfSerialIap2Link(input, output, onClose = {
                runCatching { output.close() }
                runCatching { input.close() }
            }).also { it.start() }
        }

        fun hex(b: ByteArray, off: Int, len: Int): String {
            val out = StringBuilder(len * 2)
            for (i in off until off + len) {
                val v = b[i].toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0xF])
            }
            return out.toString()
        }

        /** The bytes of an `SI` line, or null if it is not whole hex. */
        fun unhex(s: String): ByteArray? {
            if (s.length % 2 != 0) return null
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(s[2 * i], 16)
                val lo = Character.digit(s[2 * i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
