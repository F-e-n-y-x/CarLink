package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

// CarLink: the picture-sized decoder input buffer (VENDOR_CHANGES, AndroidMediaSink videoInputSize).
class VideoInputSizeTest {
    @Test fun derivesFromThePictureWithinTheBounds() {
        assertEquals(691_200, videoInputSize(1280, 720))
        assertEquals(466_944, videoInputSize(1024, 600)) // 1024 x 608 in macroblocks
        assertEquals(256 * 1024, videoInputSize(320, 240)) // floor
        assertEquals(8 * 1024 * 1024, videoInputSize(8192, 8192)) // cap
    }
}
