// CarLink: the hands-free AT exchange over fake streams (connection/HfpLink).
package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class HfpLinkTest {

    /**
     * A phone's audio gateway on the far end of the RFCOMM channel: it reads CR-terminated AT
     * commands and answers each as Android's AG does (result lines framed in CRLF, then OK).
     * [silent] answers nothing, like a phone already serving another device's hands-free link.
     */
    private class FakeGateway(private val silent: Boolean = false) {
        val commands = mutableListOf<String>()
        private val pending = StringBuilder()
        private val toHeadUnit = StringBuilder()

        val input = object : InputStream() {
            override fun available(): Int = synchronized(toHeadUnit) { toHeadUnit.length }
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(toHeadUnit) {
                val n = minOf(len, toHeadUnit.length)
                toHeadUnit.substring(0, n).toByteArray(Charsets.US_ASCII).copyInto(b, off)
                toHeadUnit.delete(0, n)
                n
            }
        }

        val output = object : OutputStream() {
            override fun write(b: Int) {
                val c = b.toChar()
                if (c != '\r') { pending.append(c); return }
                val line = pending.toString().trim()
                pending.clear()
                if (line.isEmpty()) return
                if (!line.startsWith("AT")) return // a framed answer to a question the gateway asked
                synchronized(commands) { commands += line }
                if (!silent) synchronized(toHeadUnit) { toHeadUnit.append(answer(line)) }
            }
        }

        private fun answer(command: String): String {
            val body = when {
                command.startsWith("AT+BRSF") -> listOf("+BRSF: 3943")
                command == "AT+CIND=?" -> listOf("""+CIND: ("call",(0,1)),("callsetup",(0,3)),("service",(0,1))""")
                command == "AT+CIND?" -> listOf("+CIND: 0,0,1")
                else -> emptyList()
            }
            return (body + "OK").joinToString("") { "\r\n$it\r\n" }
        }
    }

    @Test
    fun `the head unit walks the gateway to a connected hands-free link`() = runBlocking {
        val phone = FakeGateway()
        val progress = HfpLink.Progress()
        val established = AtomicInteger()
        val job = launch(Dispatchers.IO) {
            HfpLink.serve(phone.input, phone.output, initiate = true, isOpen = { true }, progress = progress) {
                established.incrementAndGet()
            }
        }
        withTimeout(5_000) {
            // Established, and the first keepalive has gone out.
            while (synchronized(phone.commands) { phone.commands.size } < 5) delay(10)
        }
        job.cancelAndJoin()

        assertEquals(HfpSlcInitiator.Stage.ESTABLISHED, progress.stage)
        assertEquals(4, progress.commandsSent)
        assertEquals(1, established.get())
        assertEquals(
            listOf("AT+BRSF=0", "AT+CIND=?", "AT+CIND?", "AT+CMER=3,0,0,1", "AT+CIND?"),
            synchronized(phone.commands) { phone.commands.take(5) }
        )
    }

    @Test
    fun `a gateway that answers nothing leaves the walk where it opened`() = runBlocking {
        val phone = FakeGateway(silent = true)
        val progress = HfpLink.Progress()
        val job = launch(Dispatchers.IO) {
            HfpLink.serve(phone.input, phone.output, initiate = true, isOpen = { true }, progress = progress)
        }
        delay(300)
        job.cancelAndJoin()

        assertEquals(HfpSlcInitiator.Stage.BRSF, progress.stage)
        assertEquals(1, progress.commandsSent)
        assertEquals(listOf("AT+BRSF=0"), phone.commands)
        assertTrue(HfpServiceRecordPolicy.standInRefusalReason(true, progress.stage)!!.contains("answered nothing"))
    }

    @Test
    fun `without initiating, the head unit speaks only when spoken to`() = runBlocking {
        val phone = FakeGateway()
        val progress = HfpLink.Progress()
        val job = launch(Dispatchers.IO) {
            HfpLink.serve(phone.input, phone.output, initiate = false, isOpen = { true }, progress = progress)
        }
        delay(300)
        job.cancelAndJoin()

        assertEquals(HfpSlcInitiator.Stage.IDLE, progress.stage)
        assertEquals(0, progress.commandsSent)
        assertTrue(phone.commands.isEmpty())
    }

    @Test
    fun `a phone acting as hands-free is answered from the table`() = runBlocking {
        val written = StringBuilder()
        val fromPhone = "AT+BRSF=20\rAT+CIND=?\r".byteInputStream(Charsets.US_ASCII)
        val sink = object : OutputStream() {
            override fun write(b: Int) { synchronized(written) { written.append(b.toChar()) } }
        }
        val job = launch(Dispatchers.IO) {
            HfpLink.serve(fromPhone, sink, initiate = false, isOpen = { true })
        }
        delay(500)
        job.cancelAndJoin()

        assertEquals(
            HfpAtResponder.replyTo("AT+BRSF=20\rAT+CIND=?\r").joinToString(""),
            synchronized(written) { written.toString() }
        )
    }
}
