package com.carlink.app.connection

import com.carlink.app.core.Engine
import com.carlink.app.core.Transport
import com.carlink.app.settings.WirelessPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-phone history: what it keeps, what it learns, and that it survives a restart. */
class ConnectionProfilesTest {
    private var wall = 1_700_000_000_000L
    private val storage = ConnectionProfiles.Storage.Memory()
    private fun store() = ConnectionProfiles(storage) { wall }

    private val iphone = SessionRequest(Engine.CARPLAY, Transport.WIRELESS, "aa:bb:cc:dd:ee:ff", "iPhone 13")
    private val s25Usb = SessionRequest(Engine.ANDROID_AUTO, Transport.USB, null, "Galaxy S25 Ultra")
    private val wd = WirelessPath.WIFI_DIRECT

    @Test fun phonesAreKeyedByEngineAndAddressOrUsbName() {
        assertEquals("CARPLAY:AA:BB:CC:DD:EE:FF", ConnectionProfiles.keyOf(iphone))
        assertEquals("ANDROID_AUTO:usb:galaxy s25 ultra", ConnectionProfiles.keyOf(s25Usb))
        assertEquals("ANDROID_AUTO:usb:phone", ConnectionProfiles.keyOf(s25Usb.copy(phoneName = null)))
    }

    @Test fun deadlinesAreTheDefaultUntilThreeSamplesThenTwoAndAHalfTimesTheMedianWithinBounds() {
        val p = store()
        val picture = ProgressStage.STARTING_PROJECTION
        assertEquals(20_000, p.deadline(iphone, wd, picture))
        p.recordStage(iphone, wd, picture, 7_000)
        p.recordStage(iphone, wd, picture, 9_000)
        assertEquals("two samples are not enough", 20_000, p.deadline(iphone, wd, picture))
        p.recordStage(iphone, wd, picture, 8_000)
        assertEquals(20_000, p.deadline(iphone, wd, picture)) // 2.5 x 8 s
        repeat(5) { p.recordStage(iphone, wd, picture, 30_000) }
        assertEquals("capped", 30_000, p.deadline(iphone, wd, picture))
        // The waiting phase over Bluetooth: a 6 s page gives 15 s.
        repeat(3) { p.recordStage(iphone, wd, ProgressStage.WAITING_FOR_PHONE, 6_000) }
        assertEquals(15_000, p.deadline(iphone, wd, ProgressStage.WAITING_FOR_PHONE))
        assertEquals("not answering is the waiting phase", 15_000, p.deadline(iphone, wd, ProgressStage.PHONE_NOT_ANSWERING))
        assertEquals(6_000L, p.usualMillis(iphone, wd, ProgressStage.WAITING_FOR_PHONE))
    }

    @Test fun anotherWirelessPathsSamplesStandInForANewPath() {
        val p = store()
        repeat(3) { p.recordStage(iphone, wd, ProgressStage.JOINING_WIFI, 8_000) }
        assertEquals(20_000, p.deadline(iphone, WirelessPath.CAR_HOTSPOT, ProgressStage.JOINING_WIFI))
        repeat(3) { p.recordStage(iphone, WirelessPath.CAR_HOTSPOT, ProgressStage.JOINING_WIFI, 1_000) }
        assertEquals("the path's own samples win, above the floor", 15_000,
            p.deadline(iphone, WirelessPath.CAR_HOTSPOT, ProgressStage.JOINING_WIFI))
        repeat(3) { p.recordStage(iphone, wd, ProgressStage.HANDSHAKE, 1_000) }
        assertEquals(6_000, p.deadline(iphone, WirelessPath.CAR_HOTSPOT, ProgressStage.HANDSHAKE))
        assertEquals("USB never borrows wireless timings", 12_000,
            p.deadline(iphone.copy(transport = Transport.USB), null, ProgressStage.HANDSHAKE))
    }

    @Test fun theWholeAttemptDeadlineLearnsFromTimeToPicture() {
        val p = store()
        assertEquals(90_000, p.deadline(iphone, wd, null))
        listOf(30_000L, 32_000L, 34_000L).forEach { p.recordSuccess(iphone, wd, it) }
        assertEquals(80_000, p.deadline(iphone, wd, null))
        listOf(4_000L, 4_000L, 4_000L).forEach { p.recordSuccess(iphone, wd, it) }
        assertEquals("never below the phases' own playbooks", 60_000, p.deadline(iphone, wd, null))
    }

    @Test fun healthSummarisesSuccessRateTimeToPictureBestPathAndLastProblems() {
        val p = store()
        p.recordSuccess(iphone, wd, 18_000)
        wall += 1_000
        p.recordFailure(iphone, wd, FailureReason.BLUETOOTH.name, ProgressStage.WAITING_FOR_PHONE, "iPhone not reachable over Bluetooth")
        wall += 1_000
        p.recordSuccess(iphone, WirelessPath.CAR_HOTSPOT, 9_000)
        p.recordSuccess(iphone, WirelessPath.CAR_HOTSPOT, 11_000)
        wall += 1_000
        p.recordProblem(iphone, wd, ProgressStage.STARTING_PROJECTION, AttemptOutcome.STALL_PICTURE, "Starting the picture took 20 s")
        val h = p.health.value.single()
        assertEquals("iPhone 13", h.phoneName)
        assertEquals(4, h.attempts)
        assertEquals(3, h.successes)
        assertEquals(0.75f, h.successRate)
        assertEquals(11_000L, h.medianTimeToPictureMillis)
        assertEquals("CAR_HOTSPOT", h.bestPath)
        assertEquals(listOf(AttemptOutcome.STALL_PICTURE, FailureReason.BLUETOOTH.name), h.lastProblems.map { it.kind })
        assertEquals(ProgressStage.WAITING_FOR_PHONE, h.lastProblems[1].stage)
        val wdHealth = h.paths.single { it.path == "WIFI_DIRECT" }
        assertEquals(2, wdHealth.attempts)
        assertEquals(false, wdHealth.lastOk)
    }

    @Test fun healthIsMostRecentlyUsedFirst() {
        val p = store()
        p.recordSuccess(iphone, wd, 10_000)
        wall += 1_000
        p.recordSuccess(s25Usb, null, 4_000)
        assertEquals(listOf("ANDROID_AUTO:usb:galaxy s25 ultra", "CARPLAY:AA:BB:CC:DD:EE:FF"), p.health.value.map { it.key })
        assertEquals("USB", p.health.value.first().bestPath)
    }

    @Test fun pathsAreRankedByPathFaultsOnly() {
        val p = store()
        val candidates = listOf(wd, WirelessPath.CAR_HOTSPOT)
        assertEquals(candidates, p.rankPaths(iphone, candidates))
        repeat(4) { p.recordFailure(iphone, wd, FailureReason.BLUETOOTH.name, ProgressStage.WAITING_FOR_PHONE, null) }
        assertEquals("Bluetooth failures are not the path's fault", candidates, p.rankPaths(iphone, candidates))
        p.recordFailure(iphone, wd, AttemptOutcome.STALL_LINKING, ProgressStage.JOINING_WIFI, null)
        p.recordFailure(iphone, wd, FailureReason.PHONE_NEVER_REACHED_CAR.name, ProgressStage.JOINING_WIFI, null)
        assertEquals(listOf(WirelessPath.CAR_HOTSPOT, wd), p.rankPaths(iphone, candidates))
        // One success after the failures lifts the penalty, but the record still counts.
        p.recordSuccess(iphone, wd, 12_000)
        p.recordSuccess(iphone, wd, 12_000)
        p.recordSuccess(iphone, wd, 12_000)
        assertEquals(candidates, p.rankPaths(iphone, candidates))
        assertEquals("a single path is never re-ordered", listOf(wd), p.rankPaths(iphone, listOf(wd)))
    }

    @Test fun historySurvivesARestart() {
        val first = store()
        repeat(3) { first.recordStage(iphone, wd, ProgressStage.HANDSHAKE, 2_000) }
        first.recordSuccess(iphone, wd, 15_000)
        first.recordFailure(s25Usb, null, FailureReason.USB.name, ProgressStage.HANDSHAKE, "unplug the phone\tand\nplug it in again")
        val second = store()
        assertEquals(first.health.value, second.health.value)
        assertEquals(6_000, second.deadline(iphone, wd, ProgressStage.HANDSHAKE))
        assertEquals("unplug the phone and plug it in again", second.health.value.single { it.engine == Engine.ANDROID_AUTO }.lastProblems.single().detail)
    }

    @Test fun ringsAndProfilesAreBounded() {
        val p = store()
        repeat(ConnectionProfiles.MAX_OUTCOMES + 7) { p.recordSuccess(iphone, wd, 10_000L + it) }
        assertEquals(ConnectionProfiles.MAX_OUTCOMES, p.health.value.single().attempts)
        repeat(ConnectionProfiles.MAX_PROBLEMS + 3) { p.recordProblem(iphone, wd, null, "X$it", null) }
        assertEquals(ConnectionProfiles.HEALTH_PROBLEMS, p.health.value.single().lastProblems.size)
        assertEquals("X${ConnectionProfiles.MAX_PROBLEMS + 2}", p.health.value.single().lastProblems.first().kind)
        repeat(ConnectionProfiles.MAX_PROFILES + 4) {
            wall += 1
            p.recordSuccess(iphone.copy(phoneAddress = "00:00:00:00:00:%02d".format(it)), wd, 10_000)
        }
        assertEquals(ConnectionProfiles.MAX_PROFILES, p.health.value.size)
    }

    @Test fun aCorruptStoreStartsEmpty() {
        storage.text = "something else entirely"
        assertTrue(store().health.value.isEmpty())
        storage.text = "carlink-profiles 1\nP\tbroken"
        assertTrue(store().health.value.isEmpty())
        assertNull(store().healthOf(iphone))
    }
}
