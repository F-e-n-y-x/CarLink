package com.carlink.app.diagnostics

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ConnectionLogTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @After fun clearEvents() = EventLog.clear()

    @Test fun masksBluetoothAddressesLikeDiAuto() {
        assertEquals("Phone [address] connected", LogMasking.mask("Phone AA:bb:01:23:45:67 connected"))
        assertEquals("id [address]", LogMasking.mask("id aa-bb-cc-dd-ee-ff"))
        // Times and versions are left alone.
        assertEquals("12:04:33.120 v1.2.3", LogMasking.mask("12:04:33.120 v1.2.3"))
    }

    @Test fun eventLogMasksAndKeepsOnlyTheNewestEntries() {
        repeat(EventLog.CAPACITY + 5) { EventLog.add("Test", "event $it") }
        EventLog.add("Bluetooth", "11:22:33:44:55:66 bonded")
        val entries = EventLog.entries.value
        assertEquals(EventLog.CAPACITY, entries.size)
        assertEquals("[address] bonded", entries.last().message)
    }

    @Test fun parsesDiAutoJournalLines() {
        val lines = ConnectionLog.parseDiAuto(listOf("1760000000000 [6] Handshake failed", "garbage", "1760000000500 [4] Started"))
        assertEquals(2, lines.size)
        assertEquals('E', lines[0].level)
        assertEquals(LogSource.ANDROID_AUTO, lines[0].source)
        assertEquals("Started", lines[1].text)
        assertEquals(1760000000500, lines[1].timeMillis)
    }

    @Test fun dropsDiAutoThreadIdPrefix() {
        val lines = ConnectionLog.parseDiAuto(listOf("1760000000000 [4] [2] Decoder stopped: New surface"))
        assertEquals("Decoder stopped: New surface", lines.single().text)
    }

    @Test fun datesDiPlayLinesFromTheHeaderAndRollsOverMidnight() {
        val lines = ConnectionLog.parseDiPlay(listOf(
            "DiPlay log started 2026-10-09 23:59:58.000 pid=1 path=x",
            "23:59:59.500  Video start failed",
            "00:00:01.000  Retrying",
        ), 0)
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        assertEquals(2, lines.size)
        assertEquals("2026-10-09 23:59:59.500", format.format(lines[0].timeMillis))
        assertEquals("2026-10-10 00:00:01.000", format.format(lines[1].timeMillis))
        assertEquals('E', lines[0].level)
    }

    @Test fun readsEngineFilesAndEventsNewestFirstAndExportsThem() {
        File(context.filesDir, "diagnostics").apply { mkdirs() }.resolve("current.log")
            .writeText("1760000001000 [4] aa first\n1760000003000 [4] aa second\n")
        EventLog.add("Coordinator", "carlink event", 1760000002000)
        val lines = ConnectionLog.read(context)
        assertEquals(listOf("aa second", "Coordinator: carlink event", "aa first"), lines.map { it.text })

        val file = ConnectionLog.export(context, "header")
        assertTrue(file.path.contains("/cache/shared/"))
        val text = file.readText()
        assertTrue(text.startsWith("header"))
        assertTrue(text.indexOf("aa first") < text.indexOf("aa second"))
    }
}
