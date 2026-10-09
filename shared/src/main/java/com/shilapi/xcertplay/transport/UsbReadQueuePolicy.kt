package com.shilapi.xcertplay.transport

import android.os.Build
import java.nio.ByteBuffer

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/**
 * Android 8.0/8.1 queue(ByteBuffer) throws above 16 KiB, so cap the first submission there.
 * The false-return fallback is a compatibility hypothesis for explicit vendor queue rejection.
 * Android 9 UsbRequest.queue(ByteBuffer) accepts any size and clears its queued state on false:
 * https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/hardware/usb/UsbRequest.java
 * Call under the pipe's state lock, including publication, queueing and the open-state checks.
 */
internal class UsbReadQueuePolicy(private val queueCeiling: Int? = null) {
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val firstBytes = minOf(
            buffer.remaining(), successfulLimit ?: buffer.remaining(), queueCeiling ?: buffer.remaining(),
        )
        buffer.limit(position + firstBytes)
        if (submit(buffer)) return UsbReadQueueResult(true, firstBytes)
        // AOSP guarantees an unchanged buffer on explicit false. Do not retry an ambiguous
        // request that threw or unexpectedly changed its buffer state.
        check(buffer.position() == position && buffer.limit() == position + firstBytes) {
            "Rejected USB queue changed its buffer state"
        }
        if (firstBytes <= COMPATIBILITY_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }
        checkOpen()
        buffer.limit(position + COMPATIBILITY_BYTES)
        val queued = submit(buffer)
        if (queued) successfulLimit = COMPATIBILITY_BYTES else buffer.limit(originalLimit)
        return UsbReadQueueResult(queued, firstBytes, COMPATIBILITY_BYTES)
    }

    companion object {
        private const val COMPATIBILITY_BYTES = 16 * 1024

        fun forCurrentPlatform(): UsbReadQueuePolicy = UsbReadQueuePolicy(
            // Android 7 uses queue(ByteBuffer, Int); Android 9 removed the new queue's bound.
            // Only API 26/27 require a ceiling before queueing, rather than a retry on false.
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.P) {
                COMPATIBILITY_BYTES
            } else {
                null
            },
        )
    }
}
