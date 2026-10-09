package com.shilapi.xcertplay.orchestration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// CarLink: new file (see docs/VENDOR_CHANGES.md).

/**
 * Runs a blocking connect on its own daemon thread from construction, so the caller can do other
 * bring-up work and collect the result later with [await]. The wireless RFCOMM dial to the iPhone
 * took 6.6 s on the user's tablet when the iPhone's Bluetooth was not yet connected; the Wi-Fi
 * Direct group, AirPlay listener and Bonjour do not depend on it, so they now start meanwhile.
 *
 * Cancelling is the caller's job (closing the socket makes the blocked connect throw).
 */
internal class BackgroundDial(
    threadName: String,
    private val nanoTime: () -> Long = System::nanoTime,
    connect: () -> Unit,
) {
    private val done = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    val startedNanos: Long = nanoTime()
    @Volatile var finishedNanos: Long? = null
        private set

    init {
        Thread(
            {
                try {
                    connect()
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finishedNanos = nanoTime()
                    done.countDown()
                }
            },
            threadName,
        ).apply {
            isDaemon = true
            start()
        }
    }

    val isDone: Boolean get() = done.count == 0L

    /**
     * Waits until [timeoutMillis] after the dial *started* (the same budget as a dial begun here).
     * Returns false on timeout, rethrows the connect failure, or returns true once connected.
     */
    fun await(timeoutMillis: Long): Boolean {
        val elapsedMillis = (nanoTime() - startedNanos) / 1_000_000L
        val remaining = (timeoutMillis - elapsedMillis).coerceAtLeast(0L)
        if (!done.await(remaining, TimeUnit.MILLISECONDS)) return false
        failure.get()?.let { throw it }
        return true
    }
}
