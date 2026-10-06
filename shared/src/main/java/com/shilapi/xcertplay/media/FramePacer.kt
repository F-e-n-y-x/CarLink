package com.shilapi.xcertplay.media

/**
 * Turns the iPhone's per-frame time into a local display time, so frames are shown at an even pace
 * instead of whenever the link and the decoder hand them over.
 *
 * The offset between the iPhone's clock and System.nanoTime is a low percentile of
 * (arrival - sender time) over recent frames: the fastest frames show the link's base delay, and late
 * frames are absorbed by the fixed delay added on top. For the first [refreshEvery] frames the offset
 * follows that percentile at once, so a slow first frame (usually a keyframe) does not set it. After
 * that it moves at most [slewPerSecond] of the elapsed time, so a fast sample leaving the window does
 * not shift every later frame at once. It re-anchors at once when the base delay grows by more than
 * [resetNanos] or shrinks by more than [maxEarlyNanos], and a frame is never held longer than the delay
 * plus [maxEarlyNanos] after it arrived.
 */
internal class FramePacer(
    private val window: Int = 240,
    private val percentile: Int = 5,
    private val refreshEvery: Int = 30,
    private val slewPerSecond: Double = 0.002,
    private val resetNanos: Long = 500_000_000L,
    private val maxEarlyNanos: Long = 100_000_000L,
) {
    private val residuals = ArrayDeque<Long>()
    private var frames = 0
    private var candidate = 0L
    private var offset = Long.MIN_VALUE
    private var lastArrivalNanos = 0L

    /**
     * The System.nanoTime at which to show a frame stamped [senderNanos] that arrived at [arrivalNanos],
     * [delayNanos] after the link's base delay; 0 to show it as soon as it is decoded (late or implausible).
     */
    fun target(senderNanos: Long, arrivalNanos: Long, delayNanos: Long): Long {
        residuals.addLast(arrivalNanos - senderNanos)
        if (residuals.size > window) residuals.removeFirst()
        val warmingUp = frames < refreshEvery
        if (warmingUp || frames % refreshEvery == 0) {
            val sorted = residuals.toLongArray().also { it.sort() }
            candidate = sorted[(sorted.size - 1) * percentile / 100]
        }
        frames++
        if (offset == Long.MIN_VALUE || warmingUp || candidate - offset > resetNanos || offset - candidate > maxEarlyNanos) {
            offset = candidate
        } else {
            val step = ((arrivalNanos - lastArrivalNanos).coerceAtLeast(0) * slewPerSecond).toLong()
            offset += (candidate - offset).coerceIn(-step, step)
        }
        lastArrivalNanos = arrivalNanos
        val target = senderNanos + offset + delayNanos
        return if (target - arrivalNanos in -ONE_SECOND..delayNanos + maxEarlyNanos) target else 0L
    }

    private companion object {
        const val ONE_SECOND = 1_000_000_000L
    }
}
