// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/blink/BlinkAutoSerialChannelTest.kt
package com.andrerinas.openheadunit.connection.blink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.AssumptionViolatedException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BlinkAutoSerialChannelTest {

    @Test
    fun `bridge script restores tty mode and cleans up its reader`() {
        val script = BlinkAutoSerialChannel.BRIDGE_SCRIPT
        assertTrue(script.contains("stty -g"))
        assertTrue(script.contains("trap cleanup"))
        assertTrue(script.contains("wait \"${'$'}READER_PID\""))
        assertTrue(script.contains("stty \"${'$'}OLD_MODE\""))
    }

    @Test
    fun `bridge startup guard only accepts a disabled and stopped stock client`() {
        val guard = BlinkAutoSerialChannel.STOCK_CLIENT_GUARD
        assertTrue(BlinkAutoSerialChannel.BRIDGE_SCRIPT.indexOf(guard) < BlinkAutoSerialChannel.BRIDGE_SCRIPT.indexOf("exec 3<>"))

        assertEquals(0, runGuard(guard, "echo package:com.syu.carlink", "return 1"))
        assertEquals(BlinkAutoSerialChannel.EXIT_STOCK_ENABLED, runGuard(guard, "return 0", "return 1"))
        assertEquals(BlinkAutoSerialChannel.EXIT_STOCK_RUNNING, runGuard(guard, "echo package:com.syu.carlink", "echo 1234; return 0"))
        assertEquals(BlinkAutoSerialChannel.EXIT_UNVERIFIED, runGuard(guard, "echo package:com.syu.carlink", "return 2"))
        assertEquals(BlinkAutoSerialChannel.EXIT_UNVERIFIED, runGuard(guard, "return 2", "return 1"))
    }

    @Test
    fun `bridge script carries no carriage return`() {
        assertFalse(BlinkAutoSerialChannel.BRIDGE_SCRIPT.contains('\r'))
    }

    @Test
    fun `the no-root channel runs the same copy loop without the guard`() {
        // Without root the guard cannot see Car Link's process, so the carrier checks the package
        // manager instead and must not pretend the script did.
        assertFalse(BlinkAutoSerialChannel.BRIDGE_BODY.contains("pm list packages"))
        assertTrue(BlinkAutoSerialChannel.BRIDGE_SCRIPT.startsWith(BlinkAutoSerialChannel.STOCK_CLIENT_GUARD))
        assertTrue(BlinkAutoSerialChannel.BRIDGE_SCRIPT.endsWith(BlinkAutoSerialChannel.BRIDGE_BODY))
    }

    @Test
    fun `guard exit codes name the refusal`() {
        assertEquals(BlinkRefusal.STOCK_CLIENT_ENABLED, BlinkAutoSerialChannel.refusalFor(7, true))
        assertEquals(BlinkRefusal.STOCK_CLIENT_RUNNING, BlinkAutoSerialChannel.refusalFor(8, true))
        assertEquals(BlinkRefusal.OWNERSHIP_UNVERIFIED, BlinkAutoSerialChannel.refusalFor(9, true))
        assertEquals(BlinkRefusal.BRIDGE_FAILED, BlinkAutoSerialChannel.refusalFor(3, true))
        // A denied su prints nothing of the script's and usually exits 1.
        assertEquals(BlinkRefusal.ROOT_DENIED, BlinkAutoSerialChannel.refusalFor(1, false))
        assertEquals(BlinkRefusal.ROOT_DENIED, BlinkAutoSerialChannel.refusalFor(null, false))
    }

    @Test
    fun `refusal retries back off and hold at the cap`() {
        assertEquals(listOf(5_000L, 30_000L, 60_000L, 60_000L), (0..3).map { BlinkRefusalBackoff.delayMs(it) })
    }

    @Test
    fun `stopping a bridge closes stdin and destroys a process that does not exit`() {
        val process = FakeProcess(terminates = false)

        stopBridgeProcess(process, process.outputStream, timeoutSeconds = 0)

        assertTrue(process.stdin.closed)
        assertTrue(process.destroyed)
    }

    @Test
    fun `closing a channel schedules process cleanup without running it on the caller`() {
        val process = FakeProcess(terminates = false)
        var cleanup: (() -> Unit)? = null
        val channel = BlinkAutoSerialChannel(process, {}, {}, cleanupScheduler = { cleanup = it })

        channel.close()

        assertTrue(channel.isFinished)
        assertFalse(process.stdin.closed)
        assertFalse(process.destroyed)
        assertTrue(cleanup != null)

        cleanup!!.invoke()
        assertTrue(process.stdin.closed)
        assertTrue(process.destroyed)
    }

    @Test
    fun `a closed handshake stream cannot write into a later phone channel`() {
        val stream = BlinkAutoSerialChannel.FrameStream { fail("closed stream sent a frame") }
        stream.close()

        expectIOException { stream.output.write(1) }
        expectIOException { stream.output.flush() }
    }

    @Test
    fun `close does not wait behind a blocked write and stops the frames after it`() {
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val sent = java.util.concurrent.atomic.AtomicInteger(0)
        val stream = BlinkAutoSerialChannel.FrameStream {
            sent.incrementAndGet()
            sendStarted.countDown()
            releaseSend.await(2, TimeUnit.SECONDS)
        }
        // Two whole frames; the first write blocks as if blink had stopped draining the pty.
        stream.output.write(BlinkAutoLine.decodeHex("000200060800" + "000200060800")!!)
        val flushFailed = AtomicBoolean(false)
        val flusher = Thread {
            try { stream.output.flush() } catch (_: IOException) { flushFailed.set(true) }
        }.apply { start() }
        assertTrue(sendStarted.await(1, TimeUnit.SECONDS))

        val closer = Thread { stream.close() }.apply { start() }
        closer.join(1_000)
        assertFalse("close waited behind a blocked write", closer.isAlive)

        releaseSend.countDown()
        flusher.join(1_000)
        assertEquals(1, sent.get())
        assertTrue(flushFailed.get())
    }

    // Windows joins arguments into one command line and leaves inner quotes unescaped, so the script goes on stdin.
    private fun runGuard(guard: String, pmBody: String, pidofBody: String): Int {
        val script = """
            pm() { $pmBody; }
            pidof() { $pidofBody; }
            $guard
        """.trimIndent()
        val process = try {
            ProcessBuilder("sh", "-s").redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw AssumptionViolatedException("no POSIX sh on this host", e)
        }
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(script + "\n") }
        process.inputStream.readBytes()
        return process.waitFor()
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeProcess(
        stdout: String = "",
        private val terminates: Boolean = true,
        private val exitCode: Int = 0,
    ) : Process() {
        val stdin = TrackingOutputStream()
        var destroyed = false
        private val stdoutBytes = stdout.toByteArray()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutBytes)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = if (destroyed) 0 else exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = terminates || destroyed
        override fun exitValue(): Int = if (terminates || destroyed) exitCode else throw IllegalThreadStateException()
        override fun destroy() {
            destroyed = true
        }
    }
}
