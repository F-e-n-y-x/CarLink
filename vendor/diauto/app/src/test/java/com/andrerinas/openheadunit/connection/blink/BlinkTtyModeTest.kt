// CarLink: ported from open-headunit 1c1b5b72 (v.3.5.0, AGPL-3.0), app/src/test/.../nativeaa/blink/BlinkTtyModeTest.kt
package com.andrerinas.openheadunit.connection.blink

import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkTtyModeTest {

    @Test
    fun `tty setup disables the transformations found in the FYT capture`() {
        val command = BlinkAutoSerialChannel.TTY_MODE_COMMAND
        for (flag in listOf("-iuclc", "-icrnl", "-inlcr", "-igncr", "-ixon", "-ixoff", "-opost")) {
            assertTrue("missing $flag in: $command", command.split(' ').contains(flag))
        }
    }
}
