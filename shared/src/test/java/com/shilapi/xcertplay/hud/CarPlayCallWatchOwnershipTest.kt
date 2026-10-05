package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarPlayCallWatchOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownership get() = CarPlayCallWatchOwnership(temporary.root)
    private fun token(packageName: String = "com.example.diplay") = File(temporary.root,
        File(CarPlayCallWatchOwnership.newToken(packageName)).name).path

    @Test fun immediateNextCallCannotReuseThePreviousWatchersToken() {
        assertNotEquals(token(), token())
    }

    @Test fun oldWatcherCannotUpdateOrEndTheNewCall() {
        val old = token()
        val next = token()
        ownership.claim(old) { it.writeText("active 1") }
        ownership.claim(next) { it.writeText("active 2") }
        var writes = 0
        assertFalse(ownership.update(old) { writes++ })
        assertFalse(ownership.retire(old) { writes++ })
        assertEquals(0, writes)
        assertFalse(File(old).exists())
        assertEquals("active 2", File(next).readText())
        assertTrue(ownership.update(next) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun anotherVariantCannotHaveItsTokenDeletedByTheOldProcess() {
        val old = token("com.example.mobile")
        val next = token("com.example.home")
        ownership.claim(old) { it.writeText("active 1") }
        ownership.claim(next) { it.writeText("ringing 0") }
        assertFalse(ownership.retire(old) { fail("Foreign hardware reset") })
        assertTrue(File(next).exists())
        assertTrue(ownership.update(next) {})
    }

    @Test fun successfulOwnedEndRetiresTheTokenAndPreventsLaterTimerWrites() {
        val path = token()
        ownership.claim(path) { it.writeText("active 1") }
        var ends = 0
        assertTrue(ownership.retire(path) { ends++ })
        assertEquals(1, ends)
        assertFalse(File(path).exists())
        assertFalse(ownership.update(path) { fail("Timer after end") })
    }

    @Test fun failingHardwareEndKeepsOwnershipForACompensationRetry() {
        val path = token()
        ownership.claim(path) { it.writeText("active 1") }
        try { ownership.retire(path) { throw IOException("write rejected") }; fail("Expected failure") }
        catch (_: IOException) {}
        assertTrue(File(path).exists())
        assertTrue(ownership.update(path) {})
        assertTrue(ownership.retire(path) {})
    }

    @Test fun callerCannotDeleteAnUnrelatedFile() {
        val unrelated = temporary.newFile("other-owner")
        try { ownership.retire(unrelated.path) {}; fail("Expected invalid token") }
        catch (_: IllegalArgumentException) {}
        assertTrue(unrelated.exists())
    }
}
