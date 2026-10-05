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

    @Test fun cleanupIsJournaledBeforeAFirstShowCanMutateOrThrow() {
        val path = token()
        try {
            ownership.claim(path) {
                assertEquals("cleanup 0", it.readText())
                throw IOException("setter failed after mutation")
            }
            fail("Expected failure")
        } catch (_: IOException) {}
        assertEquals("cleanup 0", File(path).readText())
        assertTrue(ownership.update(path) {})
    }

    @Test fun watcherCleanupFailureKeepsTheJournalAndRetriesWithoutResettingAForeignOwner() {
        val path = token()
        ownership.claim(path) { }
        var attempts = 0
        assertFalse(ownership.retireOrRetry(path) { attempts++; throw IOException("idle setter refused") })
        assertEquals("cleanup 0", File(path).readText())
        assertTrue(ownership.retireOrRetry(path) { attempts++ })
        assertEquals(2, attempts)
        assertFalse(File(path).exists())
        val old = token()
        val next = token("com.example.other")
        ownership.claim(old) { }
        ownership.claim(next) { }
        assertTrue(ownership.retireOrRetry(old) { fail("Foreign reset") })
        assertEquals("cleanup 0", File(next).readText())
    }

    @Test fun failedEndTurnsAnActiveJournalIntoPendingCleanupBeforeTheSetter() {
        val path = token()
        ownership.claim(path) { it.writeText("active 1") }
        assertFalse(ownership.retireOrRetry(path) {
            assertEquals("cleanup 0", File(path).readText())
            throw IOException("idle setter failed after mutation")
        })
        assertEquals("cleanup 0", File(path).readText())
        assertTrue(ownership.update(path) {})
    }

    @Test fun callerCannotDeleteAnUnrelatedFile() {
        val unrelated = temporary.newFile("other-owner")
        try { ownership.retire(unrelated.path) {}; fail("Expected invalid token") }
        catch (_: IllegalArgumentException) {}
        assertTrue(unrelated.exists())
    }
}
